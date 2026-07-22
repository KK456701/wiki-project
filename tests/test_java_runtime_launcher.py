from scripts.java_runtime_launcher import build_java_environment


def test_build_java_environment_maps_legacy_config_without_overriding_explicit_values() -> None:
    environment = build_java_environment(
        {
            "runtime_db_url": "mysql+pymysql://user:p%40ss@127.0.0.1:3307/runtime_db",
            "admin_password": "admin-secret",
            "business_db_source_id": "win60_qa_991827",
            "java_http_proxy_url": "http://127.0.0.1:7897",
        },
        {
            "PATH": "existing",
            "JAVA_HOME": r"C:\explicit\jdk-17",
            "WIKI_ADMIN_PASSWORD": "explicit-admin",
        },
    )

    assert environment["WIKI_RUNTIME_DB_URL"].startswith(
        "jdbc:mysql://127.0.0.1:3307/runtime_db?"
    )
    assert environment["WIKI_RUNTIME_DB_USER"] == "user"
    assert environment["WIKI_RUNTIME_DB_PASSWORD"] == "p@ss"
    assert environment["WIKI_ADMIN_PASSWORD"] == "explicit-admin"
    assert environment["DBHUB_SOURCE_ID_WIN60_QA_991827"] == "win60_qa_991827"
    assert environment["JAVA_HOME"] == r"C:\explicit\jdk-17"
    assert environment["PATH"].startswith(r"C:\explicit\jdk-17\bin")
    assert "-Dhttps.proxyHost=127.0.0.1" in environment["JAVA_TOOL_OPTIONS"]
    assert "-Dhttps.proxyPort=7897" in environment["JAVA_TOOL_OPTIONS"]
