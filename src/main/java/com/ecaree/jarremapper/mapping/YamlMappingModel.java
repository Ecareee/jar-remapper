package com.ecaree.jarremapper.mapping;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class YamlMappingModel {
    private String version;
    private List<ClassMapping> classes = new ArrayList<>();

    @Data
    @NoArgsConstructor
    public static class ClassMapping {
        private String obfuscated;
        private String readable;
        private String comment;
        private List<FieldMapping> fields = new ArrayList<>();
        private List<MethodMapping> methods = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    public static class FieldMapping {
        private String obfuscated;
        private String readable;
        private String type;
        private String comment;
    }

    @Data
    @NoArgsConstructor
    public static class MethodMapping {
        private String obfuscated;
        private String readable;
        private String descriptor;
        private String comment;
    }
}