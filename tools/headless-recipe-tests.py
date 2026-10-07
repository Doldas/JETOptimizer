#!/usr/bin/env python3
"""Isolated algorithm checks; this does not build the mod or validate Mixin application.

The original JEI bytecode loads Minecraft through DisplayIngredientAcceptor even for empty
focuses. Extract its actual matching methods from the pinned sources, omitting unrelated game
methods. Slot fixtures override ingredient access; their otherwise-unused acceptor constructor
does nothing. Normal Gradle tests use full Minecraft dependencies and no linkage fixture.
"""

import argparse
import os
from pathlib import Path
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--deps-dir", type=Path, default=Path("build/headless-deps"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    deps = args.deps_dir.resolve()
    deps.mkdir(parents=True, exist_ok=True)
    artifacts = {
        "jei-api.jar": "https://maven.blamejared.com/mezz/jei/jei-1.21.1-common-api/19.57.0.449/jei-1.21.1-common-api-19.57.0.449.jar",
        "jei-lib.jar": "https://maven.blamejared.com/mezz/jei/jei-1.21.1-lib/19.57.0.449/jei-1.21.1-lib-19.57.0.449.jar",
        "jei-lib-sources.jar": "https://maven.blamejared.com/mezz/jei/jei-1.21.1-lib/19.57.0.449/jei-1.21.1-lib-19.57.0.449-sources.jar",
        "fastutil.jar": "https://repo.maven.apache.org/maven2/it/unimi/dsi/fastutil/8.5.15/fastutil-8.5.15.jar",
        "guava.jar": "https://repo.maven.apache.org/maven2/com/google/guava/guava/33.3.1-jre/guava-33.3.1-jre.jar",
        "junit.jar": "https://repo.maven.apache.org/maven2/org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar",
    }
    for filename, url in artifacts.items():
        target = deps / filename
        if not target.is_file():
            temporary = deps / (filename + ".download")
            subprocess.run(["curl", "-fLsS", "--retry", "2", "--max-time", "120", url, "-o", str(temporary)], check=True)
            temporary.rename(target)
        if not zipfile.is_zipfile(target):
            raise RuntimeError(f"Invalid jar: {target}")

    with zipfile.ZipFile(deps / "jei-lib-sources.jar") as archive:
        source = archive.read("mezz/jei/library/ingredients/DisplayIngredientAcceptor.java").decode()
    # Both matching methods are the last methods in this pinned source. Fail if its shape changes.
    marker = "\tstatic IntSet getMatches("
    matching = source[source.index(marker):source.rfind("\n}")].replace("@Nullable", "")
    assert matching.count("static IntSet getMatches(") == 1
    assert matching.count("private static <T> boolean getMatches(") == 1
    fixture = deps / "fixture/mezz/jei/library/ingredients/DisplayIngredientAcceptor.java"
    fixture.parent.mkdir(parents=True, exist_ok=True)
    fixture.write_text("""package mezz.jei.library.ingredients;
import mezz.jei.api.ingredients.*;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.recipe.*;
import mezz.jei.api.runtime.IIngredientManager;
import it.unimi.dsi.fastutil.ints.*;
import java.util.*;
public class DisplayIngredientAcceptor {
    public DisplayIngredientAcceptor(IIngredientManager manager) {}
""" + matching + "\n}\n", encoding="utf-8")
    classes = deps / "headless-classes"
    classes.mkdir(exist_ok=True)
    classpath = os.pathsep.join(str(deps / filename) for filename in artifacts if "sources" not in filename)
    java_bin = args.java_home / "bin" if args.java_home else None
    javac = str(java_bin / "javac") if java_bin else "javac"
    java = str(java_bin / "java") if java_bin else "java"
    sources = [fixture]
    sources += [root / "src/main/java/dev/jetoptimizer" / filename for filename in
                ("RecipeVisibilityOptimization.java", "RecipeSupplierOptimization.java")]
    sources += [root / "src/test/java/dev/jetoptimizer" / filename for filename in
                ("RecipeVisibilityOptimizationTest.java", "RecipeSupplierOptimizationTest.java")]
    subprocess.run([javac, "-proc:none", "--release", "21", "-cp", classpath, "-d", str(classes),
                    *map(str, sources)], check=True)
    subprocess.run([java, "-jar", str(deps / "junit.jar"), "execute", "--class-path",
                    str(classes) + os.pathsep + classpath,
                    "--select-class", "dev.jetoptimizer.RecipeVisibilityOptimizationTest",
                    "--select-class", "dev.jetoptimizer.RecipeSupplierOptimizationTest",
                    "--disable-banner", "--details", "summary"], check=True)


if __name__ == "__main__":
    main()
