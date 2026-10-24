package com.studymate.ai.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 架构约束：业务层不得直引模型 SDK，必须统一走 LlmGateway。
 * 允许 ai.llm（模型适配器）、ai.config（框架装配）、ai.mcp（MCP 客户端适配器）依赖 SDK。
 */
class LlmSdkIsolationTest {

    private static final ArchRule BUSINESS_MUST_NOT_IMPORT_MODEL_SDK = noClasses()
            .that().resideOutsideOfPackages("com.studymate.ai.llm..", "com.studymate.ai.config..",
                    "com.studymate.ai.mcp..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework.ai..", "dev.langchain4j..")
            .because("业务层只依赖 LlmGateway，框架切换不应扩散到业务代码");

    @Test
    void businessLayerOnlyDependsOnLlmGateway() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.studymate");
        assertDoesNotThrow(() -> BUSINESS_MUST_NOT_IMPORT_MODEL_SDK.check(classes));
    }
}
