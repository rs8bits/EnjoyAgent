package com.enjoy.agent.sandbox.application;

import com.enjoy.agent.modelgateway.application.ModelGatewayToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Built-in names occupy a reserved namespace and never masquerade as remote MCP servers. */
public final class SandboxToolCatalog {
  private SandboxToolCatalog() {}

  public static final Map<String, String> OPERATIONS =
      Map.ofEntries(
          Map.entry("sandbox_python", "PYTHON"),
          Map.entry("sandbox_sql", "SQL"),
          Map.entry("sandbox_pdf_extract", "PDF_EXTRACT"),
          Map.entry("sandbox_pdf_split", "PDF_SPLIT"),
          Map.entry("sandbox_pdf_merge", "PDF_MERGE"),
          Map.entry("sandbox_pdf_render", "PDF_RENDER"),
          Map.entry("sandbox_office_convert", "OFFICE_CONVERT"),
          Map.entry("sandbox_table_inspect", "TABLE_INSPECT"),
          Map.entry("sandbox_table_analyze", "TABLE_ANALYZE"));

  public static List<ModelGatewayToolDefinition> definitions(ObjectMapper json) {
    var descriptions =
        Map.ofEntries(
            Map.entry(
                "PYTHON",
                "Run Python with preinstalled pandas, PDF libraries and matplotlib. Read selected"
                    + " files from /input/{fileId}.{ext}; write deliverables to /output. Network is"
                    + " disabled."),
            Map.entry(
                "SQL",
                "Query selected CSV/Excel files in isolated DuckDB. Tables are input_1,input_2 in"
                    + " fileIds order. External file/network access is disabled; code contains"
                    + " SQL."),
            Map.entry(
                "PDF_EXTRACT",
                "Extract PDF text; optional arguments.pages uses 1-based page numbers. Returns a"
                    + " bounded excerpt and a downloadable text artifact. Scanned PDFs require OCR,"
                    + " which is not included."),
            Map.entry(
                "PDF_SPLIT",
                "Split one PDF using arguments.ranges=[[first,last],...] or arguments.pages=[1,3]."
                    + " Page numbers are 1-based."),
            Map.entry("PDF_MERGE", "Merge PDFs in the exact fileIds order."),
            Map.entry(
                "PDF_RENDER",
                "Render PDF pages to PNG. arguments.pages defaults to [1], arguments.dpi defaults"
                    + " to 120 and is limited to 36..200."),
            Map.entry(
                "OFFICE_CONVERT",
                "Convert one Office document with LibreOffice. arguments.format: pdf (default),"
                    + " docx, xlsx or csv."),
            Map.entry(
                "TABLE_INSPECT",
                "Inspect CSV/XLS/XLSX columns, row count, sheets, missing values and a bounded"
                    + " preview. Optional sheet, encoding and separator arguments."),
            Map.entry(
                "TABLE_ANALYZE",
                "Analyze CSV/Excel and export CSV/XLSX: arguments groupBy (column list),"
                    + " aggregations (column->sum/mean/min/max/count/nunique), filters"
                    + " ([{column,operator,value}]), sortBy, ascending, limit. Filter operators:"
                    + " eq/ne/gt/gte/lt/lte/contains. No filters/aggregations produces descriptive"
                    + " statistics."));
    var result = new ArrayList<ModelGatewayToolDefinition>();
    for (var entry : new TreeMap<>(OPERATIONS).entrySet()) {
      var properties = new LinkedHashMap<String, Object>();
      properties.put(
          "fileIds",
          Map.of(
              "type",
              "array",
              "items",
              Map.of("type", "string"),
              "maxItems",
              10,
              "description",
              "Only file IDs attached to this chat or produced by its tasks; order matters."));
      properties.put(
          "arguments",
          Map.of("type", "object", "description", "Operation options as documented by this tool."));
      var required = new ArrayList<>(List.of("fileIds"));
      if (Set.of("PYTHON", "SQL").contains(entry.getValue())) {
        properties.put("code", Map.of("type", "string", "maxLength", 100000));
        required.add("code");
      }
      try {
        result.add(
            new ModelGatewayToolDefinition(
                entry.getKey(),
                descriptions.get(entry.getValue()),
                json.writeValueAsString(
                    Map.of(
                        "type",
                        "object",
                        "properties",
                        properties,
                        "required",
                        required,
                        "additionalProperties",
                        false))));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
    return List.copyOf(result);
  }
}
