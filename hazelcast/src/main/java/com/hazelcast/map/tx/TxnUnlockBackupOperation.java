/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.map.tx;

import com.hazelcast.map.operation.KeyBasedMapOperation;
import com.hazelcast.map.operation.LockAwareOperation;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.spi.*;

import java.io.IOException;

public class TxnUnlockBackupOperation extends KeyBasedMapOperation implements BackupOperation {


    public TxnUnlockBackupOperation() {
    }

    public TxnUnlockBackupOperation(String name, Data dataKey) {
        super(name, dataKey,  -1);
    }

    @Override
    public void run() {
        recordStore.unlock(dataKey, getCallerUuid(), getThreadId());
    }

    @Override
    public Object getResponse() {
        return Boolean.TRUE;
    }

    @Override
    protected void writeInternal(ObjectDataOutput out) throws IOException {
        super.writeInternal(out);
    }

    @Override
    protected void readInternal(ObjectDataInput in) throws IOException {
        super.readInternal(in);
    }
}