package com.aditya_nk.blogcms.mapper;

import com.aditya_nk.blogcms.dto.ai.AIJobStatusResponse;
import com.aditya_nk.blogcms.entity.AIJob;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Mapper for converting AIJob entity to DTO.
 *
 * Unlike the other mappers (PostMapper, CategoryMapper, ...), this one needs
 * ObjectMapper injected because AIJob.result is a raw JSON string (whatever
 * blogcms-ai-service wrote into the jsonb column) that has to be parsed into
 * a generic Object before it reaches AIJobStatusResponse — otherwise Jackson
 * re-escapes it as a string within the outer response instead of embedding
 * it as a real nested JSON object/array.
 */
@Component
@RequiredArgsConstructor
public class AIJobStatusMapper {

    private final ObjectMapper objectMapper;

    /**
     * Convert AIJob entity to AIJobStatusResponse DTO.
     *
     * @param job the AIJob entity
     * @return AIJobStatusResponse DTO
     */
    public AIJobStatusResponse toResponse(AIJob job) {
        Object result = job.getResult() != null
                ? objectMapper.readValue(job.getResult(), Object.class)
                : null;

        return AIJobStatusResponse.builder()
                .jobId(job.getId().toString())
                .status(job.getStatus())
                .result(result)
                .error(job.getError())
                .build();
    }
}
