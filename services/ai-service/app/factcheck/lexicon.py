"""A built-in lexicon of technologies, to spot a skill that appears in free text.

A bullet that says "deployed on Kubernetes" names a skill without listing it. The check scans text
for these terms and compares them with the skills the source resume has anywhere. The list is
neither complete nor meant to be: the structured `skills` list is compared in full, and this only
catches the common way a model smuggles a skill in through prose.
"""

import re

from app.factcheck.normalize import canonical_skill

# Matched case-insensitively as whole words.
_PLAIN_TERMS = (
    "java",
    "python",
    "javascript",
    "typescript",
    "kotlin",
    "scala",
    "c++",
    "c#",
    "php",
    "perl",
    "golang",
    "angular",
    "vue",
    "node.js",
    "nodejs",
    "next.js",
    "spring boot",
    "django",
    "flask",
    "fastapi",
    "kubernetes",
    "k8s",
    "docker",
    "terraform",
    "ansible",
    "jenkins",
    "aws",
    "azure",
    "gcp",
    "postgresql",
    "postgres",
    "mysql",
    "mongodb",
    "redis",
    "kafka",
    "rabbitmq",
    "pulsar",
    "elasticsearch",
    "graphql",
    "grpc",
    "sql",
    "nosql",
    "linux",
    "git",
    "ci/cd",
    "microservices",
    "machine learning",
    "tensorflow",
    "pytorch",
    "spark",
    "hadoop",
    "airflow",
    "snowflake",
    "tableau",
    "power bi",
    "figma",
    "selenium",
    "cypress",
    "junit",
    "pytest",
    "jira",
    "agile",
    "scrum",
    "oracle",
    "salesforce",
    "hibernate",
    "maven",
    "gradle",
    "nginx",
    "prometheus",
    "grafana",
    "datadog",
    "splunk",
    "cassandra",
    "dynamodb",
    "firebase",
    "flutter",
    "react native",
    "android",
    "laravel",
    "keycloak",
    "flyway",
    "openai",
    "llm",
    "devops",
    "sre",
)
# Ordinary English words as well as technologies: only matched with this exact capitalisation.
_CASED_TERMS = (
    "React",
    "Go",
    "Rust",
    "Swift",
    "Ruby",
    "Rails",
    "Spring",
    "Excel",
    "Express",
    "REST",
)


def _compile(term: str, flags: int) -> re.Pattern[str]:
    return re.compile(rf"(?<![\w+#.-]){re.escape(term)}(?![\w+#-])", flags)


_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    *((canonical_skill(t), _compile(t, re.IGNORECASE)) for t in _PLAIN_TERMS),
    *((canonical_skill(t), _compile(t, 0)) for t in _CASED_TERMS),
)

KNOWN_SKILLS = frozenset(key for key, _ in _PATTERNS)


def skills_in_text(text: str) -> dict[str, str]:
    """Canonical key -> the text as written, for every lexicon term the text mentions."""
    found: dict[str, str] = {}
    for key, pattern in _PATTERNS:
        match = pattern.search(text)
        if match and key not in found:
            found[key] = match.group(0)
    return found
