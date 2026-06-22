package com.nailinai.ragent.skill;

import com.nailinai.ragent.framework.common.BusinessException;
import com.nailinai.ragent.framework.common.ErrorCode;
import com.nailinai.ragent.dto.request.SkillCreateRequest;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SkillRegistry {

    private final Path skillRoot;
    private volatile Map<String, SkillDefinition> skills = Map.of();

    public SkillRegistry(@Value("${app.skills.root:data/skills}") String skillRoot) {
        this.skillRoot = resolveSkillRoot(skillRoot);
    }

    @PostConstruct
    void loadSkills() {
        reload();
    }

    public synchronized void reload() {
        Map<String, SkillDefinition> loaded = new LinkedHashMap<>();
        if (!Files.exists(skillRoot) || !Files.isDirectory(skillRoot)) {
            skills = Map.copyOf(loaded);
            return;
        }

        try {
            Files.list(skillRoot)
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .forEach(dir -> loadSkill(dir, loaded));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scan skill directory: " + skillRoot, exception);
        }

        skills = Map.copyOf(loaded);
    }

    public List<SkillDefinition> listSkills() {
        return new ArrayList<>(skills.values());
    }

    public List<SkillDefinition> listExecutableSkills() {
        return skills.values().stream()
                .filter(SkillDefinition::isExecutable)
                .toList();
    }

    public SkillDefinition getSkill(String name) {
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "skill name is required");
        }
        SkillDefinition definition = skills.get(name.trim());
        if (definition == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "skill not found: " + name);
        }
        return definition;
    }

    public List<SkillDefinition> findSkills(List<String> skillNames) {
        if (skillNames == null || skillNames.isEmpty()) {
            return List.of();
        }
        List<SkillDefinition> result = new ArrayList<>();
        for (String skillName : skillNames) {
            if (!StringUtils.hasText(skillName)) {
                continue;
            }
            SkillDefinition definition = skills.get(skillName.trim());
            if (definition != null) {
                result.add(definition);
            }
        }
        return result;
    }

    public String renderSkillContext(List<String> skillNames) {
        List<SkillDefinition> selected = findSkills(skillNames);
        if (selected.isEmpty()) {
            return "";
        }
        return selected.stream()
                .map(skill -> """
                        [Skill: %s]
                        Title: %s
                        Description: %s

                        %s
                        """.formatted(
                        skill.getName(),
                        fallback(skill.getTitle(), skill.getName()),
                        fallback(skill.getDescription(), "(no description)"),
                        skill.getContent().trim()
                ))
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
    }

    public synchronized SkillDefinition createSkill(SkillCreateRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "skill request is required");
        }
        String skillName = normalizeSkillName(request.getName());
        boolean overwrite = Boolean.TRUE.equals(request.getOverwrite());
        String content = buildSkillMarkdown(request);
        writeSkillFile(skillName, content, overwrite);
        reload();
        return getSkill(skillName);
    }

    public synchronized SkillDefinition updateSkill(String existingName, SkillCreateRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "skill request is required");
        }
        String currentName = normalizeSkillName(existingName);
        if (!skills.containsKey(currentName)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "skill not found: " + existingName);
        }

        String targetName = normalizeSkillName(StringUtils.hasText(request.getName()) ? request.getName() : currentName);
        boolean overwrite = true;
        SkillCreateRequest normalizedRequest = copyRequestWithName(request, targetName);
        String content = buildSkillMarkdown(normalizedRequest);

        if (!currentName.equals(targetName)) {
            moveSkillDirectory(currentName, targetName);
        }
        writeSkillFile(targetName, content, overwrite);
        reload();
        return getSkill(targetName);
    }

    public synchronized SkillDefinition importSkill(MultipartFile file, String preferredName, boolean overwrite) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "skill file is required");
        }
        String originalFilename = file.getOriginalFilename();
        if (!StringUtils.hasText(originalFilename) || !originalFilename.toLowerCase(Locale.ROOT).endsWith(".md")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "only .md skill files are supported");
        }

        String skillName = normalizeSkillName(
                StringUtils.hasText(preferredName)
                        ? preferredName
                        : stripExtension(Path.of(originalFilename).getFileName().toString())
        );
        String rawContent;
        try {
            rawContent = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read uploaded skill file", exception);
        }
        writeSkillFile(skillName, rawContent, overwrite);
        reload();
        return getSkill(skillName);
    }

    public synchronized void deleteSkill(String name) {
        String skillName = normalizeSkillName(name);
        Path targetDir = skillRoot.resolve(skillName).normalize();
        ensureInsideRoot(targetDir);
        if (!Files.exists(targetDir)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "skill not found: " + name);
        }
        try {
            Files.walk(targetDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            throw new IllegalStateException("Failed to delete skill file: " + path, exception);
                        }
                    });
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to delete skill directory: " + targetDir, exception);
        }
        reload();
    }

    private void loadSkill(Path dir, Map<String, SkillDefinition> loaded) {
        Path skillFile = dir.resolve("SKILL.md");
        if (!Files.exists(skillFile) || !Files.isRegularFile(skillFile)) {
            return;
        }
        try {
            String rawContent = Files.readString(skillFile, StandardCharsets.UTF_8);
            ParsedSkill parsedSkill = parseSkill(rawContent);
            String name = dir.getFileName().toString();
            loaded.put(name, SkillDefinition.builder()
                    .name(name)
                    .title(firstNonBlank(parsedSkill.metadata().get("title"), extractTitle(parsedSkill.body(), name)))
                    .description(firstNonBlank(parsedSkill.metadata().get("description"), extractDescription(parsedSkill.body())))
                    .content(parsedSkill.body())
                    .sourcePath(skillFile.toAbsolutePath().toString())
                    .toolName(trimToNull(parsedSkill.metadata().get("toolName")))
                    .executorType(trimToNull(parsedSkill.metadata().get("executorType")))
                    .debugQuestion(restoreFrontMatterValue(parsedSkill.metadata().get("debugQuestion")))
                    .debugArgumentsJson(restoreFrontMatterValue(parsedSkill.metadata().get("debugArgumentsJson")))
                    .debugArgumentsSchemaJson(restoreFrontMatterValue(parsedSkill.metadata().get("debugArgumentsSchemaJson")))
                    .executable(StringUtils.hasText(parsedSkill.metadata().get("toolName"))
                            && StringUtils.hasText(parsedSkill.metadata().get("executorType")))
                    .build());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read skill file: " + skillFile, exception);
        }
    }

    private ParsedSkill parseSkill(String rawContent) {
        List<String> lines = rawContent.lines().toList();
        if (lines.size() >= 3 && lines.get(0).trim().equals("---")) {
            int closingIndex = -1;
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).trim().equals("---")) {
                    closingIndex = i;
                    break;
                }
            }
            if (closingIndex > 0) {
                Map<String, String> metadata = new LinkedHashMap<>();
                for (int i = 1; i < closingIndex; i++) {
                    String line = lines.get(i).trim();
                    if (line.isBlank() || line.startsWith("#") || !line.contains(":")) {
                        continue;
                    }
                    String key = line.substring(0, line.indexOf(':')).trim();
                    String value = line.substring(line.indexOf(':') + 1).trim();
                    metadata.put(key, value);
                }
                String body = lines.subList(closingIndex + 1, lines.size()).stream().collect(Collectors.joining("\n"));
                return new ParsedSkill(metadata, body);
            }
        }
        return new ParsedSkill(Map.of(), rawContent);
    }

    private String extractTitle(String content, String fallback) {
        return content.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("#"))
                .map(line -> line.replaceFirst("^#+\\s*", "").trim())
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(fallback);
    }

    private String extractDescription(String content) {
        return content.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .filter(line -> !line.startsWith("#"))
                .findFirst()
                .orElse("");
    }

    private String fallback(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private void writeSkillFile(String skillName, String rawContent, boolean overwrite) {
        Path targetDir = skillRoot.resolve(skillName).normalize();
        ensureInsideRoot(targetDir);
        Path targetFile = targetDir.resolve("SKILL.md").normalize();
        ensureInsideRoot(targetFile);
        if (Files.exists(targetFile) && !overwrite) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "skill already exists: " + skillName);
        }
        try {
            Files.createDirectories(targetDir);
            Files.writeString(
                    targetFile,
                    normalizeLineEndings(rawContent),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write skill file: " + targetFile, exception);
        }
    }

    private void moveSkillDirectory(String sourceName, String targetName) {
        Path sourceDir = skillRoot.resolve(sourceName).normalize();
        Path targetDir = skillRoot.resolve(targetName).normalize();
        ensureInsideRoot(sourceDir);
        ensureInsideRoot(targetDir);
        if (!Files.exists(sourceDir)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "skill not found: " + sourceName);
        }
        if (Files.exists(targetDir)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "skill already exists: " + targetName);
        }
        try {
            Files.createDirectories(targetDir.getParent());
            Files.move(sourceDir, targetDir, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveException) {
            try {
                Files.move(sourceDir, targetDir);
            } catch (IOException moveException) {
                throw new IllegalStateException("Failed to rename skill directory: " + sourceDir, moveException);
            }
        }
    }

    private String buildSkillMarkdown(SkillCreateRequest request) {
        String title = firstNonBlank(trimToNull(request.getTitle()), normalizeSkillName(request.getName()));
        String description = trimToNull(request.getDescription());
        String body = trimToNull(request.getContent());
        if (!StringUtils.hasText(body)) {
            body = "# " + title + "\n";
        }

        List<String> frontMatter = new ArrayList<>();
        if (StringUtils.hasText(title)) {
            frontMatter.add("title: " + title);
        }
        if (StringUtils.hasText(description)) {
            frontMatter.add("description: " + description);
        }
        if (StringUtils.hasText(request.getToolName())) {
            frontMatter.add("toolName: " + request.getToolName().trim());
        }
        if (StringUtils.hasText(request.getExecutorType())) {
            frontMatter.add("executorType: " + request.getExecutorType().trim());
        }
        if (StringUtils.hasText(request.getDebugQuestion())) {
            frontMatter.add("debugQuestion: " + sanitizeFrontMatterValue(request.getDebugQuestion()));
        }
        if (StringUtils.hasText(request.getDebugArgumentsJson())) {
            frontMatter.add("debugArgumentsJson: " + sanitizeFrontMatterValue(request.getDebugArgumentsJson()));
        }
        if (StringUtils.hasText(request.getDebugArgumentsSchemaJson())) {
            frontMatter.add("debugArgumentsSchemaJson: " + sanitizeFrontMatterValue(request.getDebugArgumentsSchemaJson()));
        }

        if (frontMatter.isEmpty()) {
            return body.endsWith("\n") ? body : body + "\n";
        }

        return """
                ---
                %s
                ---

                %s
                """.formatted(String.join("\n", frontMatter), body.stripLeading());
    }

    private SkillCreateRequest copyRequestWithName(SkillCreateRequest request, String name) {
        SkillCreateRequest copied = new SkillCreateRequest();
        copied.setName(name);
        copied.setTitle(request.getTitle());
        copied.setDescription(request.getDescription());
        copied.setContent(request.getContent());
        copied.setToolName(request.getToolName());
        copied.setExecutorType(request.getExecutorType());
        copied.setDebugQuestion(request.getDebugQuestion());
        copied.setDebugArgumentsJson(request.getDebugArgumentsJson());
        copied.setDebugArgumentsSchemaJson(request.getDebugArgumentsSchemaJson());
        copied.setOverwrite(request.getOverwrite());
        return copied;
    }

    private String normalizeSkillName(String value) {
        String normalized = trimToNull(value);
        if (!StringUtils.hasText(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "skill name is required");
        }
        normalized = normalized.toLowerCase(Locale.ROOT)
                .replace('\\', '-')
                .replace('/', '-')
                .replace(' ', '-');
        normalized = normalized.replaceAll("[^a-z0-9_-]", "-");
        normalized = normalized.replaceAll("-{2,}", "-");
        normalized = normalized.replaceAll("^-+|-+$", "");
        if (!StringUtils.hasText(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "skill name is invalid");
        }
        return normalized;
    }

    private String stripExtension(String filename) {
        int index = filename.lastIndexOf('.');
        return index > 0 ? filename.substring(0, index) : filename;
    }

    private String normalizeLineEndings(String value) {
        return value == null ? "" : value.replace("\r\n", "\n");
    }

    private String sanitizeFrontMatterValue(String value) {
        return value == null ? "" : value.replace("\r", " ").replace("\n", "\\n").trim();
    }

    private String restoreFrontMatterValue(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? null : normalized.replace("\\n", "\n");
    }

    private void ensureInsideRoot(Path target) {
        Path normalizedRoot = skillRoot.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedRoot)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "invalid skill path");
        }
    }

    private Path resolveSkillRoot(String configuredRoot) {
        Path primary = Path.of(configuredRoot).toAbsolutePath().normalize();
        if (Files.exists(primary)) {
            return primary;
        }
        Path parentRelative = Path.of("").toAbsolutePath().resolve("..").resolve(configuredRoot).normalize();
        if (Files.exists(parentRelative)) {
            return parentRelative;
        }
        return primary;
    }

    private record ParsedSkill(Map<String, String> metadata, String body) {
    }
}
