/*******************************************************************************
 * * Copyright 2018 Impetus Infotech.
 * *
 * * Licensed under the Apache License, Version 2.0 (the "License");
 * * you may not use this file except in compliance with the License.
 * * You may obtain a copy of the License at
 * *
 * * http://www.apache.org/licenses/LICENSE-2.0
 * *
 * * Unless required by applicable law or agreed to in writing, software
 * * distributed under the License is distributed on an "AS IS" BASIS,
 * * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * * See the License for the specific language governing permissions and
 * * limitations under the License.
 ******************************************************************************/

package com.impetus.fabric.parser;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import com.impetus.blkch.BlkchnException;
import com.impetus.blkch.sql.DataFrame;
import com.impetus.blkch.sql.parser.LogicalPlan;
import com.impetus.fabric.model.Config;

public class AssetSchema {
    
    private static final Log LOGGER = LogFactory.getLog(AssetSchema.class);

    private StorageType storageType;
    
    private Map<String, String> columnDetails;
    
    private String fieldDelimiter;
    
    private String lineDelimiter;
    
    private AssetSchema(AssetSchemaBuilder builder) {
        this.storageType = builder.storageType;
        this.columnDetails = builder.columnDetails;
        this.fieldDelimiter = builder.fieldDelimiter;
        this.lineDelimiter = builder.lineDelimiter;
    }
    
    public DataFrame createDataFrame(String queryData) {
        List<List<Object>> data = new ArrayList<>();
        if(storageType == StorageType.RAW) {
            String[] records = queryData.split(lineDelimiter);
            for(String record : records) {
                data.add(Arrays.asList(record));
            }
            return new DataFrame(data, columnDetails.keySet().toArray(new String[]{}), new HashMap<>());
        } else if(storageType == StorageType.CSV) {
            String[] records = queryData.split(lineDelimiter);
            for(String record : records) {
                data.add(Arrays.asList(record.split(fieldDelimiter)));
            }
            return new DataFrame(data, columnDetails.keySet().toArray(new String[]{}), new HashMap<>());
        } else {
            Object obj;
            try {
                obj = new JSONParser().parse(queryData);
            } catch (ParseException e) {
                LOGGER.error("unable to create dataframe as query data is not parsable into json", e);
                throw new BlkchnException("unable to create dataframe as query data is not parsable into json", e);
            }
            if(obj instanceof JSONArray) {
                JSONArray arr = (JSONArray) obj;
                if(arr.size() == 0) {
                    return new DataFrame(data, new ArrayList<>(), new HashMap<>());
                }
                updateJSONColumns((JSONObject)arr.get(0));
                for(int i = 0 ; i < arr.size() ; i++) {
                    data.add(getJSONRecord((JSONObject) arr.get(i)));
                }
                return new DataFrame(data, columnDetails.keySet().toArray(new String[]{}), new HashMap<>());
            } else {
                JSONObject json = (JSONObject) obj;
                updateJSONColumns(json);
                data.add(getJSONRecord(json));
                return new DataFrame(data, columnDetails.keySet().toArray(new String[]{}), new HashMap<>());
            }
        }
    }
    
    private void updateJSONColumns(JSONObject json) {
        columnDetails = new LinkedHashMap<>();
        for(Object key : json.keySet()) {
            Object value = json.get(key);
            if(!(value instanceof JSONObject) && !(value instanceof JSONArray)) {
                columnDetails.put(key.toString(), "string");
            }
        }
    }
    
    private List<Object> getJSONRecord(JSONObject json) {
        List<Object> record = new ArrayList<>();
        for(String key : columnDetails.keySet()) {
            if(json.containsKey(key)) {
                record.add(json.get(key));
            } else {
                record.add(null);
            }
        }
        return record;
    }
    
    
    public static AssetSchema getAssetSchema(LogicalPlan logicalPlan, Config config, String asset) {
        AssetSchemaBuilder builder = new AssetSchemaBuilder();
        if(asset == null) {
            LinkedHashMap<String, String> columns = new LinkedHashMap<>();
            columns.put("data", "string");
            return builder.setColumnDetails(columns).setLineDelimiter("\n").setStorageType(StorageType.RAW).build();
        }
        if(config.getDbProperties() == null) {
            LinkedHashMap<String, String> columns = new LinkedHashMap<>();
            columns.put("data", "string");
            return builder.setColumnDetails(columns).setLineDelimiter("\n").setStorageType(StorageType.RAW).build();
        }
        String schemaJSON;
        FabricAssetManager assetManager = new FabricAssetManager(logicalPlan, config);
        try {
            schemaJSON = assetManager.getSchemaJSON(asset);
        } catch (SQLException | BlkchnException e) {
            LOGGER.error("Error reading schema json from database. Falling back to RAW storage type", e);
            LinkedHashMap<String, String> columns = new LinkedHashMap<>();
            columns.put("data", "string");
            return builder.setColumnDetails(columns).setLineDelimiter("\n").setStorageType(StorageType.RAW).build();
        }
        JSONObject json;
        try {
            json = (JSONObject)new JSONParser().parse(schemaJSON);
        } catch (ParseException e) {
            LOGGER.error("Error parsing schema json. Falling back to RAW storage type", e);
            LinkedHashMap<String, String> columns = new LinkedHashMap<>();
            columns.put("data", "string");
            return builder.setColumnDetails(columns).setLineDelimiter("\n").setStorageType(StorageType.RAW).build();
        }
        if(json.get("storageType").toString().trim().equalsIgnoreCase("CSV")) {
            builder.setStorageType(StorageType.CSV);
            JSONArray columnDetails = (JSONArray)json.get("columnDetails");
            LinkedHashMap<String, String> columns = new LinkedHashMap<>();
            for(int i = 0 ; i < columnDetails.size() ; i++) {
                JSONObject columnDetail = (JSONObject) columnDetails.get(i);
                columns.put(columnDetail.get("colName").toString().trim(), columnDetail.get("colType").toString().trim());
            }
            builder.setColumnDetails(columns);
            if(json.containsKey("fieldDelimiter")) {
                builder.setFieldDelimiter(json.get("fieldDelimiter").toString());
            } else {
                builder.setFieldDelimiter(",");
            }
            if(json.containsKey("recordDelimiter")) {
                builder.setLineDelimiter(json.get("recordDelimiter").toString());
            } else {
                builder.setLineDelimiter("\n");
            }
            return builder.build();
        } else {
            builder.setStorageType(StorageType.JSON);
            return builder.build();
        }
    }
    
    private static enum StorageType {
        JSON,
        CSV,
        RAW
    }
    
    private static class AssetSchemaBuilder {
        
        private StorageType storageType;
        
        private Map<String, String> columnDetails;
        
        private String fieldDelimiter;
        
        private String lineDelimiter;
        
        public AssetSchemaBuilder setStorageType(StorageType storageType) {
            this.storageType = storageType;
            return this;
        }
        
        public AssetSchemaBuilder setColumnDetails(LinkedHashMap<String, String> columnDetails) {
            this.columnDetails = columnDetails;
            return this;
        }
        
        public AssetSchemaBuilder setFieldDelimiter(String fieldDelimiter) {
            this.fieldDelimiter = fieldDelimiter;
            return this;
        }
        
        public AssetSchemaBuilder setLineDelimiter(String lineDelimiter) {
            this.lineDelimiter = lineDelimiter;
            return this;
        }
        
        public AssetSchema build() {
            return new AssetSchema(this);
        }
    }
}
