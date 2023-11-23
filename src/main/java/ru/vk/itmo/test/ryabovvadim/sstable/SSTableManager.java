package ru.vk.itmo.test.ryabovvadim.sstable;

import ru.vk.itmo.Entry;
import ru.vk.itmo.test.ryabovvadim.iterators.EntrySkipNullsIterator;
import ru.vk.itmo.test.ryabovvadim.iterators.FutureIterator;
import ru.vk.itmo.test.ryabovvadim.iterators.GatheringIterator;
import ru.vk.itmo.test.ryabovvadim.iterators.PriorityIterator;
import ru.vk.itmo.test.ryabovvadim.utils.FileUtils;
import ru.vk.itmo.test.ryabovvadim.utils.MemorySegmentUtils;
import ru.vk.itmo.test.ryabovvadim.utils.NumberUtils;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableSet;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import static ru.vk.itmo.test.ryabovvadim.utils.FileUtils.DATA_FILE_EXT;

public class SSTableManager {
    private final Logger log = Logger.getLogger(SSTableManager.class.getName());

    private final Arena arena = Arena.ofShared();
    private final Path path;
    private final AtomicLong nextId;
    private final NavigableSet<SafeSSTable> safeSSTables = new ConcurrentSkipListSet<>(
            Comparator.comparingLong((SafeSSTable table) -> table.ssTable().getId()).reversed()
    );
    private final ExecutorService compactWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService deleteWorker = Executors.newVirtualThreadPerTaskExecutor();

    public SSTableManager(Path path) throws IOException {
        this.path = path;
        if (Files.notExists(path)) {
            Files.createDirectories(path);
        }
        this.nextId = new AtomicLong(loadSStables());
    }

    public Entry<MemorySegment> load(MemorySegment key) {
        for (SafeSSTable safeSSTable : safeSSTables) {
            Entry<MemorySegment> entry = safeSSTable.findEntry(key);
            if (entry != null) {
                return entry;
            }
        }

        return null;
    }

    public List<FutureIterator<Entry<MemorySegment>>> load(MemorySegment from, MemorySegment to) {
        return load(from, to, null);
    }

    public List<FutureIterator<Entry<MemorySegment>>> load(MemorySegment from, MemorySegment to, Long toId) {
        List<FutureIterator<Entry<MemorySegment>>> iterators = new ArrayList<>();

        for (SafeSSTable safeSSTable : safeSSTables.reversed()) {
            if (toId != null && toId <= safeSSTable.ssTable().getId()) {
                break;
            }

            FutureIterator<Entry<MemorySegment>> iterator = safeSSTable.findEntries(from, to);
            if (iterator.hasNext()) {
                iterators.add(iterator);
            }
        }

        return iterators.reversed();
    }

    public long saveEntries(Iterable<Entry<MemorySegment>> entries) throws IOException {
        return saveEntries(entries, null);
    }

    public synchronized long saveEntries(Iterable<Entry<MemorySegment>> entries, Long reservedId) throws IOException {
        long id = reservedId == null ? nextId.getAndIncrement() : reservedId;
        boolean saved = SSTable.save(path, id, entries, arena);

        if (saved) {
            safeSSTables.add(new SafeSSTable(new SSTable(path, id, arena)));
        } else {
            id = -1;
        }

        return id;
    }

    public void compact() {
        compactWorker.submit(() -> {
            try {
                long prepareId= nextId.getAndIncrement();
                long id = saveEntries(() -> loadUntil(prepareId), prepareId);
                if (id >= 0) {
                    log.log(Level.INFO, "SSTables were compacted, new SSTable[id=%d].".formatted(id));
                } else {
                    log.log(Level.INFO, "SSTables were compacted (all entries were deleted).");
                }

                Iterator<SafeSSTable> safeSSTableIterator = safeSSTables.descendingIterator();
                while (safeSSTableIterator.hasNext()) {
                    SafeSSTable safeSSTable = safeSSTableIterator.next();
                    long curId = safeSSTable.ssTable().getId();
                    if (curId >= prepareId) {
                        break;
                    }
                    if (curId != id) {
                        safeSSTableIterator.remove();
                        deleteSSTable(safeSSTable);
                    }
                }
            } catch (IOException e) {
                log.log(Level.WARNING, "Compaction was failed", e);
            }
        });
    }

    private void deleteSSTable(SafeSSTable safeSSTable) {
        deleteWorker.submit(() -> {
            try {
                safeSSTable.delete(path);
            } catch (IOException e) {
//                log.log(
//                        Level.WARNING,
//                        "Deleting SSTable[id=%d] was failed".formatted(safeSSTable.ssTable().getId()),
//                        e
//                );
            }
        });
    }

    public void close() {
        compactWorker.close();
        deleteWorker.close();
        arena.close();
    }

    private FutureIterator<Entry<MemorySegment>> loadUntil(long toId) {
        List<FutureIterator<Entry<MemorySegment>>> loadedIterators = load(null, null, toId);

        int priority = 0;
        List<PriorityIterator<Entry<MemorySegment>>> priorityIterators = new ArrayList<>();
        for (FutureIterator<Entry<MemorySegment>> it : loadedIterators) {
            priorityIterators.add(new PriorityIterator<>(it, priority++));
        }

        GatheringIterator<Entry<MemorySegment>> gatheringIterator = new GatheringIterator<>(
                priorityIterators,
                Comparator.comparing(
                        (PriorityIterator<Entry<MemorySegment>> it) -> it.showNext().key(),
                        MemorySegmentUtils::compareMemorySegments
                ).thenComparingInt(PriorityIterator::getPriority),
                Comparator.comparing(Entry::key, MemorySegmentUtils::compareMemorySegments)
        );

        return new EntrySkipNullsIterator(gatheringIterator);
    }

    private long loadSStables() throws IOException {
        Files.walkFileTree(path, Set.of(), 1, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (FileUtils.hasExtension(file, DATA_FILE_EXT) && NumberUtils.isInteger(
                        FileUtils.extractFileName(file, DATA_FILE_EXT)
                )) {
                    long id = Long.parseLong(FileUtils.extractFileName(file, DATA_FILE_EXT));
                    safeSSTables.add(new SafeSSTable(new SSTable(path, id, arena)));
                    return FileVisitResult.CONTINUE;
                }

                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }
        });

        long maxId = -1;
        for (SafeSSTable safeSSTable : safeSSTables) {
            maxId = Math.max(maxId, safeSSTable.ssTable().getId());
        }

        return maxId + 1;
    }
}
