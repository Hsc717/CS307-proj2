package edu.sustech.cs307.system;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;


public class TransactionManager {

    private final DBManager dbManager;
    private Path transactionSnapshot;
    private boolean hasActiveTransaction;
    // Ordered list of savepoints (supports duplicate names with stack semantics)
    private final List<SavepointEntry> savepointList;

    private static class SavepointEntry {
        final String name;
        final Path snapshotPath;

        SavepointEntry(String name, Path snapshotPath) {
            this.name = name;
            this.snapshotPath = snapshotPath;
        }
    }

    public TransactionManager(DBManager dbManager) {
        this.dbManager = dbManager;
        this.hasActiveTransaction = false;
        this.savepointList = new ArrayList<>();
    }

    public boolean isTransactionActive() {
        return hasActiveTransaction;
    }

    public void begin() throws DBException {
        if (hasActiveTransaction) {
            throw new DBException(ExceptionTypes.TransactionAlreadyActive());
        }
        transactionSnapshot = createSnapshot();
        hasActiveTransaction = true;
        savepointList.clear();
    }


    public void commit() throws DBException {
        if (hasActiveTransaction) {
            dbManager.persistRuntimeState();
            hasActiveTransaction = false;
            if (transactionSnapshot != null) {
                deleteDirectoryRecursive(transactionSnapshot.toFile());
                transactionSnapshot = null;
            }
            savepointList.clear();
        }
    }


    public void rollback() throws DBException {
        if (!hasActiveTransaction) {
            return;
        }
        restoreFromSnapshot(transactionSnapshot);
        hasActiveTransaction = false;
        if (transactionSnapshot != null) {
            deleteDirectoryRecursive(transactionSnapshot.toFile());
            transactionSnapshot = null;
        }
        savepointList.clear();
    }


    public void savepoint(String savepointName) throws DBException {
        if (!hasActiveTransaction) {
            throw new DBException(ExceptionTypes.TransactionRequired());
        }
        Path savepointSnapshot = createSnapshot();
        savepointList.add(new SavepointEntry(savepointName, savepointSnapshot));
    }


    public void rollbackToSavepoint(String savepointName) throws DBException {
        if (!hasActiveTransaction) {
            throw new DBException(ExceptionTypes.TransactionRequired());
        }

        // Find the last (most recent) savepoint with this name (stack semantics)
        int targetIndex = -1;
        for (int i = savepointList.size() - 1; i >= 0; i--) {
            if (savepointList.get(i).name.equals(savepointName)) {
                targetIndex = i;
                break;
            }
        }

        if (targetIndex < 0) {
            throw new DBException(ExceptionTypes.SavepointDoesNotExist(savepointName));
        }

        SavepointEntry target = savepointList.get(targetIndex);
        // Restore from the savepoint snapshot
        restoreFromSnapshot(target.snapshotPath);

        // Remove savepoints after targetIndex
        while (savepointList.size() > targetIndex + 1) {
            SavepointEntry removed = savepointList.remove(savepointList.size() - 1);
            deleteDirectoryRecursive(removed.snapshotPath.toFile());
        }
    }


    public void releaseSavepoint(String savepointName) throws DBException {
        if (!hasActiveTransaction) {
            throw new DBException(ExceptionTypes.TransactionRequired());
        }

        // Find the last (most recent) savepoint with this name
        int targetIndex = -1;
        for (int i = savepointList.size() - 1; i >= 0; i--) {
            if (savepointList.get(i).name.equals(savepointName)) {
                targetIndex = i;
                break;
            }
        }

        if (targetIndex < 0) {
            throw new DBException(ExceptionTypes.SavepointDoesNotExist(savepointName));
        }

        // Remove this savepoint and all savepoints after it
        while (savepointList.size() > targetIndex) {
            SavepointEntry removed = savepointList.remove(savepointList.size() - 1);
            deleteDirectoryRecursive(removed.snapshotPath.toFile());
        }
    }

    private Path createSnapshot() throws DBException {
        dbManager.persistRuntimeState();
        Path snapshotDir;
        try {
            snapshotDir = Files.createTempDirectory("cs307-txn-");
            copyDirectoryContents(getDbRoot(), snapshotDir);
        } catch (IOException e) {
            throw new DBException(ExceptionTypes.BadIOError(e.getMessage()));
        }
        return snapshotDir;
    }

    private void restoreFromSnapshot(Path snapshotDir) throws DBException {
        try {
            Path dbRoot = getDbRoot();
            if (Files.exists(dbRoot)) {
                deleteDirectoryRecursive(dbRoot.toFile());
            }
            copyDirectoryContents(snapshotDir, dbRoot);
            // Reset buffer pool to discard all stale cached pages
            dbManager.getBufferPool().resetBufferPool();
            // Reload disk manager metadata from the restored files
            try {
                File metaFile = new File(dbRoot.toString(), "disk_manager_meta.json");
                if (metaFile.exists()) {
                    ObjectMapper objectMapper = new ObjectMapper();
                    TypeReference<Map<String, Integer>> typeRef = new TypeReference<>() {};
                    Map<String, Integer> loadedMeta = objectMapper.readValue(metaFile, typeRef);
                    if (loadedMeta != null) {
                        dbManager.getDiskManager().filePages.clear();
                        dbManager.getDiskManager().filePages.putAll(loadedMeta);
                    }
                }
            } catch (Exception ex) {
                // If meta file doesn't exist, that's ok for a fresh DB
            }
            // Reload metadata manager from restored files
            dbManager.getMetaManager().reloadFromDisk();
        } catch (Exception e) {
            throw new DBException(ExceptionTypes.BadIOError(e.getMessage()));
        }
    }

    private Path getDbRoot() {
        return Path.of(dbManager.getDiskManager().getCurrentDir());
    }

    private void copyDirectoryContents(Path sourceRoot, Path targetRoot) throws IOException {
        if (!Files.exists(sourceRoot)) {
            Files.createDirectories(targetRoot);
            return;
        }
        Files.createDirectories(targetRoot);
        try (var paths = Files.walk(sourceRoot)) {
            for (Path source : paths.toList()) {
                Path relative = sourceRoot.relativize(source);
                Path target = targetRoot.resolve(relative);
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private void deleteDirectoryRecursive(File file) {
        if (file.isDirectory()) {
            File[] entries = file.listFiles();
            if (entries != null) {
                for (File entry : entries) {
                    deleteDirectoryRecursive(entry);
                }
            }
        }
        file.delete();
    }
}