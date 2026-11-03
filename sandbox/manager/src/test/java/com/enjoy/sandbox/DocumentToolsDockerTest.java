package com.enjoy.sandbox;

import static org.assertj.core.api.Assertions.*;

import com.enjoy.sandbox.Protocol.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.web.MockMultipartFile;

/** Structured tools run in real, restricted containers; LibreOffice is tested in its own image. */
@EnabledIfEnvironmentVariable(named = "SANDBOX_DOCUMENT_TEST", matches = "true")
class DocumentToolsDockerTest {
  ExecutionService service;
  ManagerProperties properties;
  ObjectMapper json = new ObjectMapper();
  Map<String, byte[]> fixtures;

  @BeforeEach
  void setup() throws Exception {
    Path base = Path.of(".sandbox").toAbsolutePath();
    Files.createDirectories(base);
    Path root = Files.createTempDirectory(base, "document-test-");
    properties =
        new ManagerProperties(
            root,
            root.resolve("unused"),
            "enjoyagent-python:local",
            "doc-test-" + UUID.randomUUID(),
            2,
            1,
            1073741824,
            1,
            16777216,
            67108864,
            65536,
            24,
            "enjoyagent-documents:local");
    var docker = new DockerEngine(json);
    service =
        new ExecutionService(
            properties, docker, new DockerSandboxProvider(docker, properties), json);
    service.recover();
    var generated = run("PYTHON", fixtureCode(), new LinkedHashMap<>(), Map.of());
    assertThat(generated.status()).as(generated.stderr()).isEqualTo("SUCCEEDED");
    fixtures = contents(generated);
  }

  @AfterEach
  void cleanup() throws Exception {
    service.recover();
    ExecutionService.deleteTree(properties.workRoot());
  }

  Result run(
      String operation, String code, Map<String, byte[]> inputs, Map<String, Object> arguments)
      throws Exception {
    List<Input> manifest = new ArrayList<>();
    List<org.springframework.web.multipart.MultipartFile> files = new ArrayList<>();
    for (var entry : inputs.entrySet()) {
      String hash =
          HexFormat.of()
              .formatHex(
                  java.security.MessageDigest.getInstance("SHA-256").digest(entry.getValue()));
      manifest.add(new Input(entry.getKey(), entry.getValue().length, hash));
      files.add(
          new MockMultipartFile(
              "files", entry.getKey(), "application/octet-stream", entry.getValue()));
    }
    UUID id = UUID.randomUUID();
    service.submit(id, new Spec("1", operation, code, 120, manifest, arguments), files);
    long deadline = System.nanoTime() + Duration.ofSeconds(150).toNanos();
    while (System.nanoTime() < deadline) {
      var result = service.get(id);
      if (Set.of("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED").contains(result.status()))
        return result;
      Thread.sleep(100);
    }
    throw new AssertionError("Document execution timed out");
  }

  Map<String, byte[]> contents(Result result) throws Exception {
    var files = new LinkedHashMap<String, byte[]>();
    for (Output output : result.outputs())
      files.put(
          output.name(), Files.readAllBytes(service.output(result.executionId(), output.id())));
    return files;
  }

  JsonNode summary(Result result) throws Exception {
    assertThat(result.status()).as(result.stderr()).isEqualTo("SUCCEEDED");
    return json.readTree(contents(result).get("summary.json"));
  }

  @Test
  void extractsSplitsMergesInInputOrderAndRendersPdf() throws Exception {
    var extracted =
        run("PDF_EXTRACT", "", Map.of("first.pdf", fixtures.get("first.pdf")), Map.of());
    assertThat(summary(extracted).path("pageCount").asInt()).isEqualTo(2);
    assertThat(new String(contents(extracted).get("extracted.txt")))
        .contains("First page 1", "First page 2");
    var split =
        run(
            "PDF_SPLIT",
            "",
            Map.of("first.pdf", fixtures.get("first.pdf")),
            Map.of("ranges", List.of(List.of(1, 1), List.of(2, 2))));
    assertThat(summary(split).path("parts").asInt()).isEqualTo(2);
    assertThat(contents(split)).containsKeys("part-001.pdf", "part-002.pdf");
    var ordered = new LinkedHashMap<String, byte[]>();
    ordered.put("second.pdf", fixtures.get("second.pdf"));
    ordered.put("first.pdf", fixtures.get("first.pdf"));
    var merged = run("PDF_MERGE", "", ordered, Map.of());
    assertThat(summary(merged).path("pageCount").asInt()).isEqualTo(3);
    var reread =
        run("PDF_EXTRACT", "", Map.of("merged.pdf", contents(merged).get("merged.pdf")), Map.of());
    String text = new String(contents(reread).get("extracted.txt"));
    assertThat(text.indexOf("Second")).isLessThan(text.indexOf("First"));
    var rendered =
        run(
            "PDF_RENDER",
            "",
            Map.of("first.pdf", fixtures.get("first.pdf")),
            Map.of("pages", List.of(1), "dpi", 72));
    summary(rendered);
    byte[] png = contents(rendered).get("page-001.png");
    assertThat(png).startsWith((byte) 137, (byte) 80, (byte) 78, (byte) 71);
    var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
    assertThat(image.getWidth()).isEqualTo(300);
    assertThat(image.getHeight()).isEqualTo(200);
  }

  @Test
  void inspectsXlsxAndAggregatesCsvIntoDownloadableTables() throws Exception {
    var inspected =
        run("TABLE_INSPECT", "", Map.of("sales.xlsx", fixtures.get("sales.xlsx")), Map.of());
    assertThat(summary(inspected).path("rowCount").asInt()).isEqualTo(3);
    assertThat(summary(inspected).path("sheets").get(0).asText()).isEqualTo("Sales");
    var analyzed =
        run(
            "TABLE_ANALYZE",
            "",
            Map.of("sales.csv", fixtures.get("sales.csv")),
            Map.of("groupBy", List.of("category"), "aggregations", Map.of("amount", "sum")));
    var result = summary(analyzed);
    assertThat(result.path("resultRows").asInt()).isEqualTo(2);
    assertThat(result.path("preview").get(0).path("amount").asInt()).isEqualTo(30);
    assertThat(contents(analyzed)).containsKeys("result.xlsx", "result.csv");
    var reread =
        run(
            "TABLE_INSPECT",
            "",
            Map.of("result.xlsx", contents(analyzed).get("result.xlsx")),
            Map.of());
    assertThat(summary(reread).path("rowCount").asInt()).isEqualTo(2);
  }

  @Test
  void sqlQueriesOnlyRegisteredTablesAndBlocksExternalAccess() throws Exception {
    var queried =
        run(
            "SQL",
            "SELECT category,SUM(amount) AS total FROM input_1 GROUP BY category ORDER BY category",
            Map.of("sales.csv", fixtures.get("sales.csv")),
            Map.of());
    assertThat(summary(queried).path("preview").get(0).path("total").asInt()).isEqualTo(30);
    for (String sql :
        List.of(
            "SELECT * FROM read_csv('/etc/passwd')",
            "INSTALL httpfs; LOAD httpfs;",
            "SET enable_external_access=true; SELECT 1")) {
      var denied = run("SQL", sql, Map.of("sales.csv", fixtures.get("sales.csv")), Map.of());
      assertThat(denied.status()).as(sql).isEqualTo("FAILED");
      assertThat(denied.outputs()).isEmpty();
    }
  }

  @Test
  void officeConvertsDocxWithChineseTextToReadablePdf() throws Exception {
    var converted =
        run(
            "OFFICE_CONVERT",
            "",
            Map.of("report.docx", fixtures.get("report.docx")),
            Map.of("format", "pdf"));
    assertThat(summary(converted).path("format").asText()).isEqualTo("pdf");
    byte[] pdf = contents(converted).get("report.pdf");
    assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
    var extracted = run("PDF_EXTRACT", "", Map.of("report.pdf", pdf), Map.of());
    assertThat(summary(extracted).path("excerpt").asText()).contains("EnjoyAgent");
  }

  @Test
  void rejectsEncryptedPdfAndInvalidRanges() throws Exception {
    var encrypted =
        run("PDF_EXTRACT", "", Map.of("locked.pdf", fixtures.get("locked.pdf")), Map.of());
    assertThat(encrypted.status()).isEqualTo("FAILED");
    assertThat(encrypted.stderr()).contains("Encrypted PDF");
    var badRange =
        run(
            "PDF_SPLIT",
            "",
            Map.of("first.pdf", fixtures.get("first.pdf")),
            Map.of("ranges", List.of(List.of(2, 1))));
    assertThat(badRange.status()).isEqualTo("FAILED");
  }

  private String fixtureCode() {
    return """
from pathlib import Path
import pandas as pd,zipfile
from pypdf import PdfWriter
from pypdf.generic import DictionaryObject,NameObject,DecodedStreamObject
out=Path('/output')
for name,pages in [('first',['First page 1','First page 2']),('second',['Second page'])]:
    writer=PdfWriter()
    for text in pages:
        page=writer.add_blank_page(width=300,height=200)
        font=DictionaryObject({NameObject('/Type'):NameObject('/Font'),NameObject('/Subtype'):NameObject('/Type1'),NameObject('/BaseFont'):NameObject('/Helvetica')})
        page[NameObject('/Resources')]=DictionaryObject({NameObject('/Font'):DictionaryObject({NameObject('/F1'):writer._add_object(font)})})
        stream=DecodedStreamObject();stream.set_data(('BT /F1 14 Tf 20 100 Td ('+text+') Tj ET').encode())
        page[NameObject('/Contents')]=writer._add_object(stream)
    writer.write(out/(name+'.pdf'))
locked=PdfWriter();locked.add_blank_page(width=200,height=200);locked.encrypt('password');locked.write(out/'locked.pdf')
data=pd.DataFrame({'category':['A','A','B'],'amount':[10,20,5]})
data.to_csv(out/'sales.csv',index=False)
data.to_excel(out/'sales.xlsx',index=False,sheet_name='Sales')
with zipfile.ZipFile(out/'report.docx','w') as z:
    z.writestr('[Content_Types].xml','<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
    z.writestr('_rels/.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
    z.writestr('word/document.xml','<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>EnjoyAgent 中文文档测试</w:t></w:r></w:p></w:body></w:document>')
print('fixtures ready')
""";
  }
}
