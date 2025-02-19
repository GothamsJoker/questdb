/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
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

package io.questdb.griffin;

import io.questdb.cairo.CairoEngine;
import io.questdb.cairo.EntryUnavailableException;
import io.questdb.cairo.TableStructureChangesException;
import io.questdb.cairo.TableWriter;
import io.questdb.cairo.sql.InsertMethod;
import io.questdb.cairo.sql.InsertStatement;
import io.questdb.cairo.sql.RecordCursorFactory;
import io.questdb.cutlass.text.TextLoader;
import io.questdb.griffin.update.UpdateStatement;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.mp.FanOut;
import io.questdb.mp.RingQueue;
import io.questdb.mp.SCSequence;
import io.questdb.std.Os;
import io.questdb.std.Unsafe;
import io.questdb.std.datetime.microtime.MicrosecondClock;
import io.questdb.tasks.TableWriterTask;

public class CompiledQueryImpl implements CompiledQuery {
    private static final Log LOG = LogFactory.getLog(CompiledQueryImpl.class);
    private final CairoEngine engine;
    private final AlterTableQueryFuture alterFuture = new AlterTableQueryFuture();
    private RecordCursorFactory recordCursorFactory;
    private InsertStatement insertStatement;
    private UpdateStatement updateStatement;
    private TextLoader textLoader;
    private AlterStatement alterStatement;
    private short type;
    private SqlExecutionContext sqlExecutionContext;
    //count of rows affected by this statement ; currently works only for insert as select/create table as insert
    private long insertCount;

    public CompiledQueryImpl(CairoEngine engine) {
        this.engine = engine;
    }

    @Override
    public RecordCursorFactory getRecordCursorFactory() {
        return recordCursorFactory;
    }

    @Override
    public InsertStatement getInsertStatement() {
        return insertStatement;
    }

    @Override
    public TextLoader getTextLoader() {
        return textLoader;
    }

    @Override
    public AlterStatement getAlterStatement() {
        return alterStatement;
    }

    @Override
    public short getType() {
        return type;
    }

    @Override
    public UpdateStatement getUpdateStatement() {
        return updateStatement;
    }

    public CompiledQuery ofUpdate(UpdateStatement updateStatement) {
        this.updateStatement = updateStatement;
        this.type = UPDATE;
        return this;
    }

    @Override
    public QueryFuture execute(SCSequence eventSubSeq) throws SqlException {
        if (type == INSERT) {
            executeInsert();
            return QueryFuture.DONE;
        }

        if (type == ALTER) {
            try (
                    TableWriter writer = engine.getWriter(
                            sqlExecutionContext.getCairoSecurityContext(),
                            alterStatement.getTableName(),
                            "Alter table execute"
                    )
            ) {
                alterStatement.apply(writer, true);
                return QueryFuture.DONE;
            } catch (EntryUnavailableException busyException) {
                if (eventSubSeq == null) {
                    throw busyException;
                }
                alterFuture.of(sqlExecutionContext, eventSubSeq);
                return alterFuture;
            } catch (TableStructureChangesException e) {
                assert false : "This must never happen when parameter acceptChange=true";
            }
        }

        return QueryFuture.DONE;
    }

    @Override
    public long getInsertCount() {
        return this.insertCount;
    }

    public CompiledQuery of(short type) {
        return of(type, null);
    }

    public CompiledQuery ofLock() {
        type = LOCK;
        return this;
    }

    public CompiledQuery ofUnlock() {
        type = UNLOCK;
        return this;
    }

    public CompiledQueryImpl withContext(SqlExecutionContext executionContext) {
        sqlExecutionContext = executionContext;
        return this;
    }

    private void executeInsert() throws SqlException {
        try (InsertMethod insertMethod = insertStatement.createMethod(sqlExecutionContext)) {
            insertMethod.execute();
            insertMethod.commit();
        }
    }

    CompiledQuery of(RecordCursorFactory recordCursorFactory) {
        return of(SELECT, recordCursorFactory);
    }

    private CompiledQuery of(short type, RecordCursorFactory factory) {
        this.type = type;
        this.recordCursorFactory = factory;
        this.insertCount = -1;
        return this;
    }

    CompiledQuery ofAlter(AlterStatement statement) {
        of(ALTER);
        alterStatement = statement;
        return this;
    }

    CompiledQuery ofBackupTable() {
        return of(BACKUP_TABLE);
    }

    CompiledQuery ofCopyLocal() {
        return of(COPY_LOCAL);
    }

    CompiledQuery ofCopyRemote(TextLoader textLoader) {
        this.textLoader = textLoader;
        return of(COPY_REMOTE);
    }

    CompiledQuery ofCreateTable() {
        return of(CREATE_TABLE);
    }

    CompiledQuery ofCreateTableAsSelect(long insertCount) {
        of(CREATE_TABLE_AS_SELECT);
        this.insertCount = insertCount;
        return this;
    }

    CompiledQuery ofDrop() {
        return of(DROP);
    }

    CompiledQuery ofInsert(InsertStatement insertStatement) {
        this.insertStatement = insertStatement;
        return of(INSERT);
    }

    CompiledQuery ofInsertAsSelect(long insertCount) {
        of(INSERT_AS_SELECT);
        this.insertCount = insertCount;
        return this;
    }

    CompiledQuery ofRenameTable() {
        return of(RENAME_TABLE);
    }

    CompiledQuery ofRepair() {
        return of(REPAIR);
    }

    CompiledQuery ofSet() {
        return of(SET);
    }

    CompiledQuery ofBegin() {
        return of(BEGIN);
    }

    CompiledQuery ofCommit() {
        return of(COMMIT);
    }

    CompiledQuery ofRollback() {
        return of(ROLLBACK);
    }

    CompiledQuery ofTruncate() {
        return of(TRUNCATE);
    }

    CompiledQuery ofVacuum() {
        return of(VACUUM);
    }

    CompiledQuery ofSnapshotPrepare() {
        return of(SNAPSHOT_DB_PREPARE);
    }

    CompiledQuery ofSnapshotComplete() {
        return of(SNAPSHOT_DB_COMPLETE);
    }

    private class AlterTableQueryFuture implements QueryFuture {
        private SCSequence eventSubSeq;
        private int status;
        private long commandId;
        private QueryFutureUpdateListener queryFutureUpdateListener;

        @Override
        public void await() throws SqlException {
            status = await(engine.getConfiguration().getWriterAsyncCommandBusyWaitTimeout());
            if (status == QUERY_STARTED) {
                status = await(engine.getConfiguration().getWriterAsyncCommandMaxTimeout() - engine.getConfiguration().getWriterAsyncCommandBusyWaitTimeout());
            }
            if (status != QUERY_COMPLETE) {
                throw SqlException.$(alterStatement.getTableNamePosition(), "Timeout expired on waiting for the ALTER TABLE execution result");
            }
        }

        @Override
        public int await(long timeout) throws SqlException {
            if (status == QUERY_COMPLETE) {
                return status;
            }
            return status = Math.max(status, awaitWriterEvent(timeout, alterStatement.getTableNamePosition()));
        }

        @Override
        public int getStatus() {
            return status;
        }

        @Override
        public void close() {
            if (eventSubSeq != null) {
                engine.getMessageBus().getTableWriterEventFanOut().remove(eventSubSeq);
                eventSubSeq.clear();
                eventSubSeq = null;
                commandId = -1;
            }
        }

        /***
         * Initializes instance of AlterTableQueryFuture with the parameters to wait for the new command
         * @param eventSubSeq - event sequence used to wait for the command execution to be signaled as complete
         */
        public void of(SqlExecutionContext executionContext, SCSequence eventSubSeq) throws SqlException {
            assert eventSubSeq != null : "event subscriber sequence must be provided";

            this.queryFutureUpdateListener = executionContext.getQueryFutureUpdateListener();
            // Set up execution wait sequence to listen to the Engine async writer events
            final FanOut writerEventFanOut = engine.getMessageBus().getTableWriterEventFanOut();
            writerEventFanOut.and(eventSubSeq);

            try {
                this.eventSubSeq = eventSubSeq;
                try {
                    // Publish new command and get published Command Id
                    commandId = publishTableWriterCommand(alterStatement);
                    queryFutureUpdateListener.reportStart(alterStatement.getTableName(), commandId);
                    status = QUERY_NO_RESPONSE;
                } catch (TableStructureChangesException e) {
                    assert false : "Should never throw TableStructureChangesException when executed with acceptStructureChange=true";
                }
            } catch (Throwable throwable) {
                engine.getMessageBus().getTableWriterEventFanOut().remove(eventSubSeq);
                throw throwable;
            }
        }

        private long publishTableWriterCommand(AlterStatement alterTableStatement) throws TableStructureChangesException, SqlException {
            CharSequence tableName = alterTableStatement.getTableName();
            final long commandCorrelationId = engine.getCommandCorrelationId();
            alterTableStatement.setCommandCorrelationId(commandCorrelationId);
            try (TableWriter writer = engine.getWriterOrPublishCommand(sqlExecutionContext.getCairoSecurityContext(), tableName, "alter table", alterTableStatement)) {
                if (writer != null) {
                    alterTableStatement.apply(writer, true);
                }
            }

            LOG.info()
                    .$("published ASYNC writer ALTER TABLE task [table=").$(tableName)
                    .$(",instance=").$(commandCorrelationId)
                    .I$();
            return commandCorrelationId;
        }

        private int awaitWriterEvent(
                long writerAsyncCommandBusyWaitTimeout,
                int queryTableNamePosition
        ) throws SqlException {
            assert eventSubSeq != null : "No sequence to wait on";
            assert commandId > -1 : "No command id to wait for";

            final MicrosecondClock clock = engine.getConfiguration().getMicrosecondClock();
            final long start = clock.getTicks();
            final RingQueue<TableWriterTask> tableWriterEventQueue = engine.getMessageBus().getTableWriterEventQueue();

            int status = this.status;
            while (true) {
                long seq = eventSubSeq.next();
                if (seq < 0) {
                    // Queue is empty, check if the execution blocked for too long
                    if (clock.getTicks() - start > writerAsyncCommandBusyWaitTimeout) {
                        return status;
                    }
                    Os.pause();
                    continue;
                }

                try {
                    TableWriterTask event = tableWriterEventQueue.get(seq);
                    int type = event.getType();
                    if (event.getInstance() != commandId || (type != TableWriterTask.TSK_ALTER_TABLE_BEGIN && type != TableWriterTask.TSK_ALTER_TABLE_COMPLETE)) {
                        LOG.debug()
                                .$("writer command response received and ignored [instance=").$(event.getInstance())
                                .$(", type=").$(type)
                                .$(", expectedInstance=").$(commandId)
                                .I$();
                        Os.pause();
                    } else if (type == TableWriterTask.TSK_ALTER_TABLE_COMPLETE) {
                        // If writer failed to execute the ALTER command it will send back string error
                        // in the event data
                        LOG.info().$("writer command response received [instance=").$(commandId).I$();
                        int strLen = Unsafe.getUnsafe().getInt(event.getData());
                        if (strLen > -1) {
                            throw SqlException.$(queryTableNamePosition, event.getData() + 4L, event.getData() + 4L + 2L * strLen);
                        }
                        queryFutureUpdateListener.reportProgress(commandId, QUERY_COMPLETE);
                        return QUERY_COMPLETE;
                    } else {
                        status = QUERY_STARTED;
                        queryFutureUpdateListener.reportProgress(commandId, QUERY_STARTED);
                        LOG.info().$("writer command QUERY_STARTED response received [instance=").$(commandId).I$();
                    }
                } finally {
                    eventSubSeq.done(seq);
                }
            }
        }
    }
}
