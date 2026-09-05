# Java release templates

These 58 Java template files are copied unchanged from the Contracts 1.0 release payload at canonical release commit `5dc8096` (`release-support/blue-contracts-1.0/payload/java-templates`). They are release-shell inputs, separate from the published runtime modules. The package validator compiles them with Java 8 compatibility and executes all three reference shape smoke programs.

`regenerate_package.py` includes the exact files under `java-templates` in every complete staged release. The staging regression compares their complete inventory and bytes. No external checkout is needed to reconstruct the release shell.
