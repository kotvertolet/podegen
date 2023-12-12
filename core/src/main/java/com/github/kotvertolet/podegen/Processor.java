package com.github.kotvertolet.podegen;

import com.github.kotvertolet.podegen.annotations.PageObject;
import com.github.kotvertolet.podegen.builder.CodeGeneratorBuilder;
import com.github.kotvertolet.podegen.data.PageObjectTemplate;
import com.github.kotvertolet.podegen.data.enums.Extension;
import com.github.kotvertolet.podegen.parsers.JsonParser;
import com.github.kotvertolet.podegen.parsers.Parser;
import com.github.kotvertolet.podegen.parsers.YamlParser;
import com.github.kotvertolet.podegen.utils.Config;
import com.google.auto.service.AutoService;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.Resource;
import io.github.classgraph.ResourceList;
import io.github.classgraph.ScanResult;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.io.IOException;
import java.util.Set;

@SupportedSourceVersion(SourceVersion.RELEASE_17)
@SupportedAnnotationTypes({
        "com.github.kotvertolet.podegen.annotations.PageObject"
})
@AutoService(javax.annotation.processing.Processor.class)
public class Processor extends AbstractProcessor {

    public final static String SUPPORTED_FILE_PREFIX = "POGE_";
    public final static String SUPPORTED_FORMATS_PATTERN = "(\\.yaml|\\.yml|\\.json)";
    public final static String POGE_FILES_PATTERN = String.format("^.*(%s.*%s)", SUPPORTED_FILE_PREFIX, SUPPORTED_FORMATS_PATTERN);
    private Types typeUtils;
    private Elements elementUtils;
    private Filer filer;
    private Messager messager;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        typeUtils = processingEnv.getTypeUtils();
        elementUtils = processingEnv.getElementUtils();
        filer = processingEnv.getFiler();
        messager = processingEnv.getMessager();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        var annotationsSet = roundEnv.getElementsAnnotatedWith(PageObject.class);
        if (!annotationsSet.isEmpty()) {
            if (annotationsSet.size() > 1) {
                throw new RuntimeException("More than one PageObject annotations aren't allowed");
            }
            Config.initConfig(annotationsSet.stream().findAny().get().getAnnotation(PageObject.class));

            ScanResult scanResult = new ClassGraph().acceptModules().disableJarScanning().disableNestedJarScanning()
                    .disableRuntimeInvisibleAnnotations().scan();
            try (scanResult) {
                scanForPodegenFiles(scanResult).stream().map(this::processPageObjTemplateFile).forEach(this::generateCode);
            }
            return true;
        }
        return false;
    }

    private ResourceList scanForPodegenFiles(ScanResult scanResult) {
        ResourceList rawPageFiles = scanResult.getAllResources().filter(resource ->
                resource.getPath().matches(POGE_FILES_PATTERN));
        if (!rawPageFiles.isEmpty()) {
            return rawPageFiles;
        } else {
            throw new RuntimeException("No suitable page object files were found");
        }
    }

    private void generateCode(PageObjectTemplate pageObjectTemplate) {
        Config conf = Config.getInstance();
        TypeSpec pageObjectClass = CodeGeneratorBuilder.builder()
                .addFlavour(conf.getFlavourType())
                .addStrategy(conf.getStrategyType())
                .addPageObjectTemplate(pageObjectTemplate)
                .generateCode();
        try {
            JavaFile.builder(this.getClass().getPackageName() + "." + pageObjectTemplate.packages(), pageObjectClass)
                    .build()
                    .writeTo(filer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private PageObjectTemplate processPageObjTemplateFile(Resource rawPogeFile) {
        return getAppropriateParser(rawPogeFile.getPath()).parse(rawPogeFile);
    }

    private Parser getAppropriateParser(String path) {
        return switch (getFileExtension(path)) {
            case YML, YAML -> new YamlParser();
            case JSON -> new JsonParser();
        };
    }

    private Extension getFileExtension(String path) {
        String[] splitPath = path.split("\\.");
        if (splitPath.length == 2) {
            return Extension.get(splitPath[1]);
        } else throw new RuntimeException("Incorrect path supplied: " + path);
    }
}
