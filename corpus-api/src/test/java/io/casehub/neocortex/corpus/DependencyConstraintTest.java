package io.casehub.neocortex.corpus;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "io.casehub.neocortex.corpus")
class DependencyConstraintTest {

    @ArchTest
    static final ArchRule noQuarkus = noClasses().should()
        .dependOnClassesThat().resideInAnyPackage("io.quarkus..", "jakarta..");

    @ArchTest
    static final ArchRule noLangChain4j = noClasses().should()
        .dependOnClassesThat().resideInAnyPackage("dev.langchain4j..");

    @ArchTest
    static final ArchRule noQdrant = noClasses().should()
        .dependOnClassesThat().resideInAnyPackage("io.qdrant..");

    @ArchTest
    static final ArchRule noZip4j = noClasses().should()
        .dependOnClassesThat().resideInAnyPackage("net.lingala.zip4j..");

    @ArchTest
    static final ArchRule noCasehubDomain = noClasses()
        .that().resideInAPackage("io.casehub.neocortex.corpus..")
        .should().dependOnClassesThat(
            DescribedPredicate.describe("casehub domain classes",
                (JavaClass cls) -> cls.getPackageName().startsWith("io.casehub.")
                    && !cls.getPackageName().startsWith("io.casehub.neocortex.corpus")));
}
