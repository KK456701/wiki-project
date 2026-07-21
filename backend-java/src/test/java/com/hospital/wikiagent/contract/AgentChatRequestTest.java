package com.hospital.wikiagent.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class AgentChatRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsUnicodeUploadFileKeyAndTrimsText() {
        AgentChatRequest request = new AgentChatRequest(
                "  分析刚上传的文件  ",
                " session-1 ",
                " deepseek-v4-pro ",
                " hospital_001_无标题.xlsx ");

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.query()).isEqualTo("分析刚上传的文件");
        assertThat(request.fileKey()).isEqualTo("hospital_001_无标题.xlsx");
    }

    @Test
    void rejectsPathSeparatorAndBlankQuery() {
        AgentChatRequest request = new AgentChatRequest("   ", "  ", null, "../other.xlsx");

        Set<ConstraintViolation<AgentChatRequest>> violations = validator.validate(request);

        assertThat(violations).extracting(violation -> violation.getPropertyPath().toString())
                .contains("query", "sessionId", "fileKey");
    }
}
