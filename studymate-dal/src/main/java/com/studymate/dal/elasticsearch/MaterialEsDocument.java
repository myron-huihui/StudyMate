package com.studymate.dal.elasticsearch;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.LocalDateTime;

/**
 * ES 全文索引文档：按"子块"建索引，保留 parentId 便于回溯父块上下文。
 */
@Data
@Document(indexName = "studymate_materials")
public class MaterialEsDocument {

    @Id
    private String id;
    private Long materialId;
    private Integer chunkIndex;
    /** 父块 ID（父子分块），子块检索命中后可回取父块 */
    private String parentId;
    @Field(type = FieldType.Text, analyzer = "standard")
    private String title;
    @Field(type = FieldType.Text, analyzer = "standard")
    private String summary;
    @Field(type = FieldType.Keyword)
    private String category;
    /** 子块内容，用于 BM25 */
    @Field(type = FieldType.Text, analyzer = "standard")
    private String content;
    /** 父块内容（可选冗余，避免二次查询） */
    @Field(type = FieldType.Text, analyzer = "standard")
    private String parentContent;
    private Integer difficulty;
    private LocalDateTime createdAt;
}
