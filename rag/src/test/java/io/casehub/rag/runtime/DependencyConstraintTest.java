package io.casehub.rag.runtime;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "io.casehub.rag.runtime")
class DependencyConstraintTest {

    @ArchTest
    static final ArchRule noCasehubDomainBeyondPlatformApi = noClasses()
        .that().resideInAPackage("io.casehub.rag.runtime..")
        .should().dependOnClassesThat(
            DescribedPredicate.describe("casehub domain classes beyond platform-api and rag-api",
                (JavaClass cls) -> {
                    String pkg = cls.getPackageName();
                    return pkg.startsWith("io.casehub.")
                        && !pkg.startsWith("io.casehub.rag")
                        && !pkg.startsWith("io.casehub.inference")
                        && !pkg.startsWith("io.casehub.platform.api");
                }));
}
