# Agent Instructions for dichtbij3d Backend

## Project Context
This is a Java/Maven backend repository.

## Commands
- **Compile:** `mvn compile`
- **Test:** `mvn test`
- **Build/Package:** `mvn clean install`
- **Run (Docker):** `docker-compose up` (if applicable)

## Agent Rules
- Always run `mvn test` before concluding a task to ensure nothing is broken.
- Follow the existing project structure (e.g., `src/main/java`, `src/test/java`).
- Only modify `pom.xml` if explicitly requested to add/update dependencies or plugins.
- Do not commit any changes without user permission.
