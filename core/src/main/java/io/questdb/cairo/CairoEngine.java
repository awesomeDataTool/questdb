/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2020 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cairo;

import static io.questdb.cairo.ColumnType.SYMBOL;

import java.io.Closeable;

import org.jetbrains.annotations.Nullable;

import io.questdb.MessageBus;
import io.questdb.cairo.pool.PoolListener;
import io.questdb.cairo.pool.ReaderPool;
import io.questdb.cairo.pool.WriterPool;
import io.questdb.cairo.sql.ReaderOutOfDateException;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.mp.SynchronizedJob;
import io.questdb.std.Files;
import io.questdb.std.FilesFacade;
import io.questdb.std.Misc;
import io.questdb.std.Transient;
import io.questdb.std.microtime.MicrosecondClock;
import io.questdb.std.str.Path;

public class CairoEngine implements Closeable {
    private static final Log LOG = LogFactory.getLog(CairoEngine.class);

    private final WriterPool writerPool;
    private final ReaderPool readerPool;
    private final CairoConfiguration configuration;
    private final WriterMaintenanceJob writerMaintenanceJob;
    private final MessageBus messageBus;

    public CairoEngine(CairoConfiguration configuration) {
        this(configuration, null);
    }

    public CairoEngine(CairoConfiguration configuration, @Nullable MessageBus messageBus) {
        this.configuration = configuration;
        this.writerPool = new WriterPool(configuration, messageBus);
        this.readerPool = new ReaderPool(configuration);
        this.writerMaintenanceJob = new WriterMaintenanceJob(configuration);
        this.messageBus = messageBus;
    }

    public WriterMaintenanceJob getWriterMaintenanceJob() {
        return writerMaintenanceJob;
    }

    @Override
    public void close() {
        Misc.free(writerPool);
        Misc.free(readerPool);
    }

    public void creatTable(
            CairoSecurityContext securityContext,
            AppendMemory mem,
            Path path,
            TableStructure struct
    ) {
        securityContext.checkWritePermission();
        TableUtils.createTable(
                configuration.getFilesFacade(),
                mem,
                path,
                configuration.getRoot(),
                struct,
                configuration.getMkDirMode()
        );
    }

    public int getBusyReaderCount() {
        return readerPool.getBusyCount();
    }

    public int getBusyWriterCount() {
        return writerPool.getBusyCount();
    }

    public CairoConfiguration getConfiguration() {
        return configuration;
    }

    public PoolListener getPoolListener() {
        return this.writerPool.getPoolListener();
    }

    public void setPoolListener(PoolListener poolListener) {
        this.writerPool.setPoolListener(poolListener);
        this.readerPool.setPoolListener(poolListener);
    }

    public TableReader getReader(
            CairoSecurityContext securityContext,
            CharSequence tableName
    ) {
        return getReader(securityContext, tableName, TableUtils.ANY_TABLE_VERSION);
    }

    public TableReader getReader(
            CairoSecurityContext securityContext,
            CharSequence tableName,
            long version
    ) {
        TableReader reader = readerPool.get(tableName);
        if (version > -1 && reader.getVersion() != version) {
            reader.close();
            throw ReaderOutOfDateException.INSTANCE;
        }
        return reader;
    }

    public int getStatus(
            CairoSecurityContext securityContext,
            Path path,
            CharSequence tableName,
            int lo,
            int hi
    ) {
        return TableUtils.exists(configuration.getFilesFacade(), path, configuration.getRoot(), tableName, lo, hi);
    }

    public int getStatus(
            CairoSecurityContext securityContext,
            Path path,
            CharSequence tableName
    ) {
        return getStatus(securityContext, path, tableName, 0, tableName.length());
    }

    public TableWriter getWriter(
            CairoSecurityContext securityContext,
            CharSequence tableName
    ) {
        securityContext.checkWritePermission();
        return writerPool.get(tableName);
    }

    public TableWriter getBackupWriter(
            CairoSecurityContext securityContext,
            CharSequence tableName,
            CharSequence backupDirName
    ) {
        securityContext.checkWritePermission();
        // There is no point in pooling/caching these writers since they are only used once, backups are not incremental
        return new TableWriter(configuration, tableName, messageBus, true, DefaultLifecycleManager.INSTANCE, backupDirName);
    }

    public boolean lock(
            CairoSecurityContext securityContext,
            CharSequence tableName
    ) {
        securityContext.checkWritePermission();
        if (writerPool.lock(tableName)) {
            boolean locked = readerPool.lock(tableName);
            if (locked) {
                return true;
            }
            writerPool.unlock(tableName);
        }
        return false;
    }

    public boolean lockReaders(CharSequence tableName) {
        return readerPool.lock(tableName);
    }

    public boolean migrateNullFlag(CairoSecurityContext cairoSecurityContext, CharSequence tableName) {
        try (
                TableWriter writer = getWriter(cairoSecurityContext, tableName);
                TableReader reader = getReader(cairoSecurityContext, tableName)
        ) {
            TableReaderMetadata readerMetadata = (TableReaderMetadata) reader.getMetadata();
            if (readerMetadata.getVersion() < 416) {
                LOG.info().$("migrating null flag for symbols [table=").utf8(tableName).$(']').$();
                for (int i = 0, count = reader.getColumnCount(); i < count; i++) {
                    if (readerMetadata.getColumnType(i) == SYMBOL) {
                        LOG.info().$("updating null flag [column=").utf8(readerMetadata.getColumnName(i)).$(']').$();
                        writer.getSymbolMapWriter(i).updateNullFlag(reader.hasNull(i));
                    }
                }
                writer.updateMetadataVersion();
                LOG.info().$("migrated null flag for symbols [table=").utf8(tableName).$(", tableVersion=").$(ColumnType.VERSION).$(']').$();
                return true;
            }
        }
        return false;
    }

    public boolean releaseAllReaders() {
        return readerPool.releaseAll();
    }

    public boolean releaseAllWriters () {
        return writerPool.releaseAll();
    }
    
	public boolean releaseInactive() {
		boolean useful = writerPool.releaseInactive();
		useful |= readerPool.releaseInactive();
		return useful;
	}

    public void remove(
            CairoSecurityContext securityContext,
            Path path,
            CharSequence tableName
    ) {
        securityContext.checkWritePermission();
        if (lock(securityContext, tableName)) {
            try {
                path.of(configuration.getRoot()).concat(tableName).$();
                if (!configuration.getFilesFacade().rmdir(path)) {
                    int error = configuration.getFilesFacade().errno();
                    LOG.error().$("remove failed [tableName='").utf8(tableName).$("', error=").$(error).$(']').$();
                    throw CairoException.instance(error).put("Table remove failed");
                }
                return;
            } finally {
                unlock(securityContext, tableName, null);
            }
        }
        throw CairoException.instance(configuration.getFilesFacade().errno()).put("Could not lock '").put(tableName).put('\'');
    }

    public boolean removeDirectory(@Transient Path path, CharSequence dir) {
        path.of(configuration.getRoot()).concat(dir);
        final FilesFacade ff = configuration.getFilesFacade();
        return ff.rmdir(path.put(Files.SEPARATOR).$());
    }

    public void rename(
            CairoSecurityContext securityContext,
            Path path,
            CharSequence tableName,
            Path otherPath,
            CharSequence newName
    ) {
        securityContext.checkWritePermission();
        if (lock(securityContext, tableName)) {
            try {
                rename0(path, tableName, otherPath, newName);
            } finally {
                unlock(securityContext, tableName, null);
            }
        } else {
            LOG.error().$("cannot lock and rename [from='").$(tableName).$("', to='").$(newName).$("']").$();
            throw CairoException.instance(0).put("Cannot lock [table=").put(tableName).put(']');
        }
    }

    public void unlock(
            CairoSecurityContext securityContext,
            CharSequence tableName,
            @Nullable TableWriter writer
    ) {
        readerPool.unlock(tableName);
        writerPool.unlock(tableName, writer);
    }

    public void unlockReaders(CharSequence tableName) {
        readerPool.unlock(tableName);
    }

    private void rename0(Path path, CharSequence tableName, Path otherPath, CharSequence to) {
        final FilesFacade ff = configuration.getFilesFacade();
        final CharSequence root = configuration.getRoot();

        if (TableUtils.exists(ff, path, root, tableName) != TableUtils.TABLE_EXISTS) {
            LOG.error().$('\'').utf8(tableName).$("' does not exist. Rename failed.").$();
            throw CairoException.instance(0).put("Rename failed. Table '").put(tableName).put("' does not exist");
        }

        path.of(root).concat(tableName).$();
        otherPath.of(root).concat(to).$();

        if (ff.exists(otherPath)) {
            LOG.error().$("rename target exists [from='").$(tableName).$("', to='").$(otherPath).$("']").$();
            throw CairoException.instance(0).put("Rename target exists");
        }

        if (!ff.rename(path, otherPath)) {
            int error = ff.errno();
            LOG.error().$("rename failed [from='").$(path).$("', to='").$(otherPath).$("', error=").$(error).$(']').$();
            throw CairoException.instance(error).put("Rename failed");
        }
    }

    private class WriterMaintenanceJob extends SynchronizedJob {

        private final MicrosecondClock clock;
        private final long checkInterval;
        private long last = 0;

        public WriterMaintenanceJob(CairoConfiguration configuration) {
            this.clock = configuration.getMicrosecondClock();
            this.checkInterval = configuration.getIdleCheckInterval() * 1000;
        }

        protected boolean doRun() {
            return releaseInactive();
        }

        @Override
        protected boolean runSerially() {
            long t = clock.getTicks();
            if (last + checkInterval < t) {
                last = t;
                return doRun();
            }
            return false;
        }
    }
}
