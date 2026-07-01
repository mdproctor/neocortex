package io.casehub.neocortex.inference.runtime;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "io.casehub.neocortex.inference.runtime")
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

    // ONNX Runtime and DJL ARE allowed — this module wraps them

    @ArchTest
    static final ArchRule noCasehubDomain = noClasses()
        .that().resideInAPackage("io.casehub.neocortex.inference.runtime..")
        .should().dependOnClassesThat(
            DescribedPredicate.describe("casehub domain classes",
                (JavaClass cls) -> cls.getPackageName().startsWith("io.casehub.")
                    && !cls.getPackageName().startsWith("io.casehub.neocortex.inference")));
}
