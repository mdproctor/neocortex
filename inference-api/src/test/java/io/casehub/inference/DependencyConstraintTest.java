package io.casehub.inference;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "io.casehub.inference")
class DependencyConstraintTest {

    @ArchTest
    static final ArchRule noQuarkus = noClasses().should()
        .dependOnClassesThat().resideInAnyPackage("io.quarkus..", "jakarta..");

    @ArchTest
    static final ArchRule noSpring = noClasses().should()
        .dependOnClassesThat().resideInAnyPackage("org.springframework..");

    @ArchTest
    static final ArchRule noLangChain4j = noClasses().should()
        .dependOnClassesThat().resideInAnyPackage("dev.langchain4j..");

    @ArchTest
    static final ArchRule noOnnxRuntime = noClasses().should()
        .dependOnClassesThat().resideInAnyPackage("ai.onnxruntime..");

    @ArchTest
    static final ArchRule noDjl = noClasses().should()
        .dependOnClassesThat().resideInAnyPackage("ai.djl..");
}
