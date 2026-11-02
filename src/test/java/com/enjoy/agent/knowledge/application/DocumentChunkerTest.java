package com.enjoy.agent.knowledge.application;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class DocumentChunkerTest {
  @Test
  void prefersParagraphBoundariesAndRetainsSourceOffsets() {
    var p = new KnowledgeProperties();
    p.setChunkSize(80);
    p.setChunkOverlap(8);
    var c = new DocumentChunker(p);
    String text = "# 安装说明\n\n" + "第一段正文。".repeat(7) + "\n\n" + "第二段正文。".repeat(9);
    var chunks = c.chunk(text);
    var locations = c.locate(text, chunks);
    assertThat(chunks).hasSizeGreaterThan(1);
    for (int i = 0; i < chunks.size(); i++)
      assertThat(text.substring(locations.get(i).sourceStart(), locations.get(i).sourceEnd()))
          .isEqualTo(chunks.get(i));
    assertThat(locations.getFirst().heading()).isEqualTo("安装说明");
    assertThat(chunks.getFirst().length()).isLessThanOrEqualTo(80);
  }

  @Test
  void recordsPdfPagesAndDoesNotSplitSurrogates() {
    var p = new KnowledgeProperties();
    p.setChunkSize(20);
    p.setChunkOverlap(0);
    var c = new DocumentChunker(p);
    String text = "第一页\n\f\n第二页";
    assertThat(c.locate(text, List.of("第二页")).getFirst().pageNumber()).isEqualTo(2);
    for (String chunk : c.chunk("😀".repeat(30))) {
      assertThat(Character.isLowSurrogate(chunk.charAt(0))).isFalse();
      assertThat(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1))).isFalse();
    }
  }
}
