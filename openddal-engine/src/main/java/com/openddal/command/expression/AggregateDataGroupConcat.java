/*
 * Copyright 2014-2016 the original author or authors
 *
 * Licensed under the Apache License, Version 2.0 (the “License”);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an “AS IS” BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.openddal.command.expression;

import com.openddal.engine.Database;
import com.openddal.util.New;
import com.openddal.util.ValueHashMap;
import com.openddal.value.Value;
import com.openddal.value.ValueNull;

import java.util.ArrayList;

/**
 * Data stored while calculating a GROUP_CONCAT aggregate.
 */
class AggregateDataGroupConcat extends AggregateData {
    private ArrayList<Value> list;
    private ValueHashMap<AggregateDataGroupConcat> distinctValues;

    @Override
    void add(Database database, int dataType, boolean distinct, Value v) {
        if (v == ValueNull.INSTANCE) {
            return;
        }
        if (distinct) {
            if (distinctValues == null) {
                distinctValues = ValueHashMap.newInstance();
            }
            distinctValues.put(v, this);
            return;
        }
        if (list == null) {
            list = New.arrayList();
        }
        list.add(v);
    }

    @Override
    Value getValue(Database database, int dataType, boolean distinct) {
        if (distinct) {
            groupDistinct(database, dataType);
        }
        return null;
    }

    ArrayList<Value> getList() {
        return list;
    }

    private void groupDistinct(Database database, int dataType) {
        if (distinctValues == null) {
            return;
        }
        for (Value v : distinctValues.keys()) {
            add(database, dataType, false, v);
        }
    }
}
