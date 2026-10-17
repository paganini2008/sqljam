/*
 * Copyright 2023-2026 Fred Feng
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.sqljam.db2;

import com.github.sqljam.it.AbstractParquetIT;
import com.github.sqljam.it.ItDatabase;

/**
 * @Description: Db2ParquetIT
 * @Author: Fred Feng
 * @Date: 17/10/2026
 * @Version 1.0.0
 */
class Db2ParquetIT extends AbstractParquetIT {

    Db2ParquetIT() {
        super(ItDatabase.DB2);
    }
}
