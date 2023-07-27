// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.flink.sink.writer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import org.apache.commons.codec.binary.Base64;
import org.apache.doris.flink.cfg.DorisOptions;
import org.apache.doris.flink.exception.IllegalArgumentException;
import org.apache.doris.flink.rest.RestService;
import org.apache.doris.flink.sink.HttpGetWithEntity;
import org.apache.doris.flink.tools.cdc.mysql.MysqlType;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.util.StringUtils;
import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.doris.flink.sink.writer.LoadConstants.DORIS_DELETE_SIGN;

public class JsonDebeziumSchemaSerializer implements DorisRecordSerializer<String> {

    private static final Logger LOG = LoggerFactory.getLogger(JsonDebeziumSchemaSerializer.class);
    private static final String CHECK_SCHEMA_CHANGE_API = "http://%s/api/enable_light_schema_change/%s/%s";
    private static final String SCHEMA_CHANGE_API = "http://%s/api/query/default_cluster/%s";
    private static final String OP_READ = "r"; // snapshot read
    private static final String OP_CREATE = "c"; // insert
    private static final String OP_UPDATE = "u"; // update
    private static final String OP_DELETE = "d"; // delete

    public static final String EXECUTE_DDL = "ALTER TABLE %s %s COLUMN %s %s"; //alter table tbl add cloumn aca int
    private static final String addDropDDLRegex = "ALTER\\s+TABLE\\s+[^\\s]+\\s+(ADD|DROP)\\s+(COLUMN\\s+)?([^\\s]+)(\\s+([^\\s]+))?.*";
    private final Pattern addDropDDLPattern;
    private DorisOptions dorisOptions;
    private ObjectMapper objectMapper = new ObjectMapper();
    private String database;
    private String table;
    //table name of the cdc upstream, format is db.tbl
    private String sourceTableName;
    private String ddlOp;
    private String ddlColumnName;
    private boolean isDropColumn;

    public JsonDebeziumSchemaSerializer(DorisOptions dorisOptions, Pattern pattern, String sourceTableName) {
        this.dorisOptions = dorisOptions;
        this.addDropDDLPattern = pattern == null ? Pattern.compile(addDropDDLRegex, Pattern.CASE_INSENSITIVE) : pattern;
        String[] tableInfo = dorisOptions.getTableIdentifier().split("\\.");
        this.database = tableInfo[0];
        this.table = tableInfo[1];
        this.sourceTableName = sourceTableName;
        // Prevent loss of decimal data precision
        this.objectMapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        JsonNodeFactory jsonNodeFactory = JsonNodeFactory.withExactBigDecimals(true);
        this.objectMapper.setNodeFactory(jsonNodeFactory);
    }

    @Override
    public byte[] serialize(String record) throws IOException {
        LOG.debug("received debezium json data {} :", record);
        JsonNode recordRoot = objectMapper.readValue(record, JsonNode.class);
        String op = extractJsonNode(recordRoot, "op");
        if (Objects.isNull(op)) {
            //schema change ddl
            schemaChange(recordRoot);
            return null;
        }
        Map<String, String> valueMap;
        switch (op) {
            case OP_READ:
            case OP_CREATE:
            case OP_UPDATE:
                valueMap = extractAfterRow(recordRoot);
                addDeleteSign(valueMap, false);
                break;
            case OP_DELETE:
                valueMap = extractBeforeRow(recordRoot);
                addDeleteSign(valueMap, true);
                break;
            default:
                LOG.error("parse record fail, unknown op {} in {}", op, record);
                return null;
        }
        return objectMapper.writeValueAsString(valueMap).getBytes(StandardCharsets.UTF_8);
    }

    @VisibleForTesting
    public boolean schemaChange(JsonNode recordRoot) {
        boolean status = false;
        try{
            if(!StringUtils.isNullOrWhitespaceOnly(sourceTableName) && !checkTable(recordRoot)){
                return false;
            }
            String ddl = extractDDL(recordRoot);
            if(StringUtils.isNullOrWhitespaceOnly(ddl)){
                LOG.info("ddl can not do schema change:{}", recordRoot);
                return false;
            }
            boolean doSchemaChange = checkSchemaChange();
            status = doSchemaChange && execSchemaChange(ddl);
            LOG.info("schema change status:{}", status);
        }catch (Exception ex){
            LOG.warn("schema change error :", ex);
        }
        return status;
    }

    /**
     * When cdc synchronizes multiple tables, it will capture multiple table schema changes
     */
    protected boolean checkTable(JsonNode recordRoot) {
        String db = extractDatabase(recordRoot);
        String tbl = extractTable(recordRoot);
        String dbTbl = db + "." + tbl;
        return sourceTableName.equals(dbTbl);
    }

    private void addDeleteSign(Map<String, String> valueMap, boolean delete) {
        if(delete){
            valueMap.put(DORIS_DELETE_SIGN, "1");
        }else{
            valueMap.put(DORIS_DELETE_SIGN, "0");
        }
    }

    private boolean checkSchemaChange() throws IOException, IllegalArgumentException {
        String requestUrl = String.format(CHECK_SCHEMA_CHANGE_API, RestService.randomEndpoint(dorisOptions.getFenodes(), LOG), database, table);
        Map<String,Object> param = buildRequestParam();
        if(param.size() != 2){
            return false;
        }
        HttpGetWithEntity httpGet = new HttpGetWithEntity(requestUrl);
        httpGet.setHeader(HttpHeaders.AUTHORIZATION, authHeader());
        httpGet.setEntity(new StringEntity(objectMapper.writeValueAsString(param)));
        boolean success = handleResponse(httpGet);
        if (!success) {
            LOG.warn("schema change can not do table {}.{}",database,table);
        }
        return success;
    }

    /**
     * Build param
     * {
     * "isDropColumn": true,
     * "columnName" : "column"
     * }
     */
    protected Map<String, Object> buildRequestParam() {
        Map<String,Object> params = new HashMap<>();
        params.put("isDropColumn", this.isDropColumn);
        params.put("columnName", this.ddlColumnName);
        return params;
    }

    private boolean execSchemaChange(String ddl) throws IOException, IllegalArgumentException {
        Map<String, String> param = new HashMap<>();
        param.put("stmt", ddl);
        String requestUrl = String.format(SCHEMA_CHANGE_API, RestService.randomEndpoint(dorisOptions.getFenodes(), LOG), database);
        HttpPost httpPost = new HttpPost(requestUrl);
        httpPost.setHeader(HttpHeaders.AUTHORIZATION, authHeader());
        httpPost.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
        httpPost.setEntity(new StringEntity(objectMapper.writeValueAsString(param)));
        return handleResponse(httpPost);
    }

    protected String extractDatabase(JsonNode record) {
        if(record.get("source").has("schema")){
            //compatible with schema
            return extractJsonNode(record.get("source"), "schema");
        }else{
            return extractJsonNode(record.get("source"), "db");
        }
    }

    protected String extractTable(JsonNode record) {
        return extractJsonNode(record.get("source"), "table");
    }

    private boolean handleResponse(HttpUriRequest request) {
        try (CloseableHttpClient httpclient = HttpClients.createDefault()) {
            CloseableHttpResponse response = httpclient.execute(request);
            final int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode == 200 && response.getEntity() != null) {
                String loadResult = EntityUtils.toString(response.getEntity());
                Map<String, Object> responseMap = objectMapper.readValue(loadResult, Map.class);
                String code = responseMap.getOrDefault("code", "-1").toString();
                if (code.equals("0")) {
                    return true;
                } else {
                    LOG.error("schema change response:{}", loadResult);
                }
            }
        }catch(Exception e){
            LOG.error("http request error,", e);
        }
        return false;
    }

    private String extractJsonNode(JsonNode record, String key) {
        return record != null && record.get(key) != null && !(record.get(key) instanceof NullNode) ?
                record.get(key).asText() : null;
    }

    private Map<String, String> extractBeforeRow(JsonNode record) {
        return extractRow(record.get("before"));
    }

    private Map<String, String> extractAfterRow(JsonNode record) {
        return extractRow(record.get("after"));
    }

    private Map<String, String> extractRow(JsonNode recordRow) {
        Map<String, String> recordMap = objectMapper.convertValue(recordRow, new TypeReference<Map<String, String>>() {
        });
        return recordMap != null ? recordMap : new HashMap<>();
    }

    public String extractDDL(JsonNode record) throws JsonProcessingException {
        JsonNode historyRecord = objectMapper.readTree(extractJsonNode(record, "historyRecord"));
        JsonNode tableChanges = historyRecord.get("tableChanges").get(0);
        if (Objects.isNull(tableChanges)|| !tableChanges.get("type").asText().equals("ALTER")) {
            return null;
        }
        String ddl = extractJsonNode(historyRecord, "ddl");
        LOG.debug("received debezium ddl :{}", ddl);
        return parseDDL(ddl, tableChanges);
    }

    /**
     * currently not supported changing multiple columns.
     */
    private String parseDDL(String ddl, JsonNode tableChanges) {
        Matcher matcher = addDropDDLPattern.matcher(ddl);
        if (matcher.find()) {
            this.ddlOp = matcher.group(1);
            this.ddlColumnName = matcher.group(3);
            this.isDropColumn = ddlOp.equalsIgnoreCase("DROP");
        }

        String defaultValue = "";
        String comment = "";
        String type = "";
        if (!isDropColumn) {
            JsonNode columns = tableChanges.get("table").get("columns");
            JsonNode lastDDLNode = columns.get(columns.size() - 1);
            ddlColumnName = extractJsonNode(lastDDLNode, "name");
            type = extractJsonNode(lastDDLNode, "typeName");
            JsonNode length = lastDDLNode.get("length");
            JsonNode scale = lastDDLNode.get("scale");
            type = MysqlType.toDorisType(type, length == null ? 0 : length.asInt(), scale == null ? 0 : scale.asInt());
            defaultValue = handleDefaultValue(extractJsonNode(lastDDLNode, "defaultValueExpression"));
            comment = extractJsonNode(lastDDLNode, "comment");
        }

        String alterDDL = String.format(EXECUTE_DDL, dorisOptions.getTableIdentifier(), ddlOp, ddlColumnName, type);
        if (!StringUtils.isNullOrWhitespaceOnly(defaultValue)) {
            alterDDL = alterDDL + " default " + defaultValue;
        }
        if (!StringUtils.isNullOrWhitespaceOnly(comment)) {
            alterDDL = alterDDL + " comment " + comment;
        }
        LOG.info("parsed alterDDL:{}", alterDDL);
        return alterDDL;
    }

    /**
     * Due to historical reasons, doris needs to add quotes to the default value of the new column.
     */
    private String handleDefaultValue(String defaultValue) {
        if (StringUtils.isNullOrWhitespaceOnly(defaultValue)) {
            return null;
        }
        if (Pattern.matches("['\"].*?['\"]", defaultValue)) {
            return defaultValue;
        } else if (defaultValue.equals("1970-01-01 00:00:00")) {
            // The default value of setting the current time in CDC is 1970-01-01 00:00:00
            return "current_timestamp";
        }
        return "'" + defaultValue + "'";
    }

    private String authHeader() {
        return "Basic " + new String(Base64.encodeBase64((dorisOptions.getUsername() + ":" + dorisOptions.getPassword()).getBytes(StandardCharsets.UTF_8)));
    }

    public static JsonDebeziumSchemaSerializer.Builder builder() {
        return new JsonDebeziumSchemaSerializer.Builder();
    }

    /**
     * Builder for JsonDebeziumSchemaSerializer.
     */
    public static class Builder {
        private DorisOptions dorisOptions;
        private Pattern addDropDDLPattern;
        private String sourceTableName;

        public JsonDebeziumSchemaSerializer.Builder setDorisOptions(DorisOptions dorisOptions) {
            this.dorisOptions = dorisOptions;
            return this;
        }

        public JsonDebeziumSchemaSerializer.Builder setPattern(Pattern addDropDDLPattern) {
            this.addDropDDLPattern = addDropDDLPattern;
            return this;
        }

        public JsonDebeziumSchemaSerializer.Builder setSourceTableName(String sourceTableName) {
            this.sourceTableName = sourceTableName;
            return this;
        }

        public JsonDebeziumSchemaSerializer build() {
            return new JsonDebeziumSchemaSerializer(dorisOptions, addDropDDLPattern, sourceTableName);
        }
    }
}