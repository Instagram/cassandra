/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.rocksdb.streaming;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import org.junit.AfterClass;
import org.junit.BeforeClass;

import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.rocksdb.RocksDBConfigs;
import org.apache.cassandra.rocksdb.RocksDBTestBase;
import org.apache.cassandra.streaming.StreamSession;
import org.apache.cassandra.utils.FBUtilities;
import org.rocksdb.RocksDB;

public class RocksDBStreamTestBase extends RocksDBTestBase
{

    @BeforeClass
    public static void classSetUp()
    {
        RocksDBTestBase.classSetUp();
        RocksDBConfigs.NUM_SHARD = 8;
        Path streamDir = Paths.get("/tmp/rocksdbstream/", UUID.randomUUID().toString());
        RocksDBConfigs.STREAMING_TMPFILE_PATH = new File(streamDir.toString());
        if (RocksDBConfigs.STREAMING_TMPFILE_PATH.exists())
        {
            FileUtils.deleteRecursive(RocksDBConfigs.STREAMING_TMPFILE_PATH);
        }
        FileUtils.createDirectory(streamDir.toString());
    }

    @AfterClass
    public static void tearDownClass() {
        RocksDBTestBase.tearDownClass();
        RocksDBConfigs.NUM_SHARD = 1;
    }

    public static StreamSession createDummySession()
    {
        return new StreamSession(FBUtilities.getLocalAddress(), FBUtilities.getLocalAddress(), null, 0, false, false);
    }

    public boolean inRanges(Token token, List<Range<Token>> ranges)
    {
        for (Range<Token> range : ranges)
        {
            if ( token.compareTo(range.left) >= 0 && token.compareTo(range.right) < 0)
            {
                return true;
            }
        }
        return false;
    }
}
