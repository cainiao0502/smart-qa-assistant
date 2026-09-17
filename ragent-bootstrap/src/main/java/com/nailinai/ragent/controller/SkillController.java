package com.nailinai.ragent.controller;

import com.nailinai.ragent.agent.dto.ToolContext;
import com.nailinai.ragent.agent.dto.ToolExecutionResult;
import com.nailinai.ragent.framework.common.BusinessException;
import com.nailinai.ragent.framework.common.ErrorCode;
import com.nailinai.ragent.framework.common.Result;
import com.nailinai.ragent.dto.request.ChatRequest;
import com.nailinai.ragent.dto.request.SkillCreateRequest;
import com.nailinai.ragent.dto.request.SkillExecuteRequest;
import com.nailinai.ragent.dto.response.SkillDetailResponse;
import com.nailinai.ragent.dto.response.SkillDefinitionResponse;
import com.nailinai.ragent.dto.response.SkillExecuteResponse;
import com.nailinai.ragent.skill.SkillDefinition;
import com.nailinai.ragent.skill.SkillRegistry;
import com.nailinai.ragent.skill.SkillToolExecutorFactory;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillRegistry skillRegistry;
    private final SkillToolExecutorFactory skillToolExecutorFactory;

    public SkillController(SkillRegistry skillRegistry,
                           SkillToolExecutorFactory skillToolExecutorFactory) {
        this.skillRegistry = skillRegistry;
        this.skillToolExecutorFactory = skillToolExecutorFactory;
    }

    @GetMapping
    public Result<List<SkillDefinitionResponse>> listSkills() {
        return Result.success(skillRegistry.listSkills().stream()
                .map(skill -> SkillDefinitionResponse.builder()
                        .name(skill.getName())
                        .title(skill.getTitle())
                        .description(skill.getDescription())
                        .toolName(skill.getToolName())
                        .executorType(skill.getExecutorType())
                        .executable(skill.isExecutable())
                        .build())
                .toList());
    }

    @GetMapping("/{name}")
    public Result<SkillDetailResponse> getSkill(@PathVariable String name) {
        return Result.success(toDetailResponse(skillRegistry.getSkill(name)));
    }

    @PostMapping
    public Result<SkillDetailResponse> createSkill(@RequestBody SkillCreateRequest request) {
        return Result.success(toDetailResponse(skillRegistry.createSkill(request)));
    }

    @PutMapping("/{name}")
    public Result<SkillDetailResponse> updateSkill(@PathVariable String name, @RequestBody SkillCreateRequest request) {
        return Result.success(toDetailResponse(skillRegistry.updateSkill(name, request)));
    }

    @DeleteMapping("/{name}")
    public Result<Void> deleteSkill(@PathVariable String name) {
        skillRegistry.deleteSkill(name);
        return Result.success();
    }

    @PostMapping("/import")
    public Result<SkillDetailResponse> importSkill(@RequestParam("file") MultipartFile file,
                                                   @RequestParam(value = "name", required = false) String name,
                                                   @RequestParam(value = "overwrite", defaultValue = "false") boolean overwrite) {
        return Result.success(toDetailResponse(skillRegistry.importSkill(file, name, overwrite)));
    }

    @PostMapping("/{name}/execute")
    public Result<SkillExecuteResponse> executeSkill(@PathVariable String name,
                                                     @RequestBody SkillExecuteRequest request) {
        SkillDefinition skill = skillRegistry.getSkill(name);
        if (!skillToolExecutorFactory.supports(skill)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "skill is not executable: " + name);
        }
        ChatRequest chatRequest = new ChatRequest();
        chatRequest.setKbId(null);
        chatRequest.setSessionId("skill-debug-" + UUID.randomUUID().toString().substring(0, 8));
        chatRequest.setQuestion(request == null || request.getQuestion() == null ? "" : request.getQuestion().trim());

        ToolExecutionResult result = skillToolExecutorFactory.create(skill).execute(
                request == null ? null : request.getArguments(),
                ToolContext.builder()
                        .request(chatRequest)
                        .history(List.of())
                        .retrievedChunks(List.of())
                        .build()
        );
        return Result.success(SkillExecuteResponse.builder()
                .skillName(skill.getName())
                .question(chatRequest.getQuestion())
                .summary(result.getSummary())
                .output(result.getObservation())
                .trace(result.getTrace())
                .build());
    }

    @PostMapping("/reload")
    public Result<Void> reloadSkills() {
        skillRegistry.reload();
        return Result.success();
    }

    private SkillDetailResponse toDetailResponse(SkillDefinition skill) {
        return SkillDetailResponse.builder()
                .name(skill.getName())
                .title(skill.getTitle())
                .description(skill.getDescription())
                .sourcePath(skill.getSourcePath())
                .content(skill.getContent())
                .toolName(skill.getToolName())
                .executorType(skill.getExecutorType())
                .debugQuestion(skill.getDebugQuestion())
                .debugArgumentsJson(skill.getDebugArgumentsJson())
                .debugArgumentsSchemaJson(skill.getDebugArgumentsSchemaJson())
                .executable(skill.isExecutable())
                .build();
    }
}
