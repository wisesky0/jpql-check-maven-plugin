# jpql-check-maven-plugin

## 프로젝트 개요

**이름**: jpql-check-maven-plugin

**목적**: Spring Data JPA 2.x(Hibernate 5) → 3.x(Hibernate 6) 마이그레이션 후 발생하는 QueryDSL Expressions.*Template(..) 기반 JPQL 런타임 타입 불일치 오류를 빌드 시점에 정적 분석으로 사전 감지하는 Maven 플러그인.

## 감지 대상 오류

### 오류 1 (규칙 D-01)
Hibernate 6 등록 함수(ABS, LENGTH 등)의 인자 위치에 타입 비호환 QueryDSL Path가 바인딩된 경우.

예:
```java
Expressions.numberTemplate(Long.class, "ABS({0})", qEntity.stringColumn)
```
→ `org.hibernate.query.sqm.internal.TypecheckUtil` 런타임 예외 발생

### 오류 2 (규칙 D-03, D-08)
Hibernate 미등록 네이티브 함수(DATE_FORMAT 등)를 포함한 템플릿 표현식을 QueryDSL Path 계열과 비교 연산에 사용한 경우. 반환 타입이 Object로 추론되어 TypecheckUtil 런타임 예외 발생.

- Path와의 직접 비교(D-03)
- 로컬 변수 경유 비교(D-08)

모두 감지합니다.

## 감지 제외 (우회 패턴)

- **오류 1 우회**: cast 적용 — `"ABS(cast({0} as long))"` 형식
- **오류 2 우회**: 
  - `function()` 구문 — `"function('DATE_FORMAT', {0}, {1})"` 형식
  - Hibernate 6 표준 format 함수 — `"format({0} as 'yyyyMMdd')"` 형식

## 모듈 구성

- **jpql-check-processor**: javac 어노테이션 프로세서 + Compiler Tree API 기반 AST 분석 엔진
- **jpql-check-maven-plugin**: Maven Mojo — 프로세서를 빌드에 연결하고 결과에 따라 빌드 실패 처리

## 요구사항

- Java 17 이상
- Maven 3.9 이상
- QueryDSL 5.x (querydsl-jpa jakarta)
- Spring Data JPA 3.x / Hibernate 6.x

## 설치 및 사용법

### 1단계: 대상 프로젝트의 pom.xml에 어노테이션 프로세서 의존성 추가 (provided 스코프)

```xml
<dependency>
    <groupId>io.github.wisesky0</groupId>
    <artifactId>jpql-check-processor</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <scope>provided</scope>
</dependency>
```

### 2단계: maven-compiler-plugin에 프로세서 옵션 추가

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>io.github.wisesky0</groupId>
                <artifactId>jpql-check-processor</artifactId>
                <version>1.0.0-SNAPSHOT</version>
            </path>
        </annotationProcessorPaths>
        <compilerArgs>
            <arg>-Ajpql.check.reportDir=${project.build.directory}/jpql-check</arg>
            <arg>-Ajpql.check.formats=json,html</arg>
        </compilerArgs>
    </configuration>
</plugin>
```

### 3단계: Maven 플러그인 추가 (선택 — 빌드 실패 처리가 필요한 경우)

```xml
<plugin>
    <groupId>io.github.wisesky0</groupId>
    <artifactId>jpql-check-maven-plugin</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <executions>
        <execution>
            <goals><goal>check</goal></goals>
        </execution>
    </executions>
    <configuration>
        <failOnError>true</failOnError>
        <failOnWarning>false</failOnWarning>
        <reportDirectory>${project.build.directory}/jpql-check</reportDirectory>
        <reportFormats>
            <format>json</format>
            <format>html</format>
        </reportFormats>
    </configuration>
</plugin>
```

### 오류를 출력하되 빌드를 중지하지 않는 설정 (리포트 전용 모드)

마이그레이션 초기에 감지 건수가 많을 때 유용합니다. `jpql.check.failOnError=false`를 주면
감지된 오류가 컴파일러 **WARNING**으로 출력되어 컴파일이 중지되지 않고 끝까지 진행되며,
리포트(json/html/sarif)에는 심각도가 **ERROR**로 그대로 기록됩니다.

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>io.github.wisesky0</groupId>
                <artifactId>jpql-check-processor</artifactId>
                <version>1.0.0-SNAPSHOT</version>
            </path>
        </annotationProcessorPaths>
        <compilerArgs>
            <arg>-Ajpql.check.reportDir=${project.build.directory}/jpql-check</arg>
            <arg>-Ajpql.check.formats=json,html</arg>
            <!-- 오류를 WARNING으로 출력하여 컴파일을 중지하지 않음 -->
            <arg>-Ajpql.check.failOnError=false</arg>
        </compilerArgs>
    </configuration>
</plugin>
```

빌드 실패 처리까지 완전히 끄려면 3단계의 Maven 플러그인 설정도 함께 변경합니다.

```xml
<configuration>
    <failOnError>false</failOnError>
</configuration>
```

프로파일로 분리하면 평소에는 리포트만 수집하고 CI에서만 엄격하게 실패시킬 수 있습니다.

```xml
<profiles>
    <!-- 기본: 오류를 출력하되 빌드는 계속 진행 -->
    <profile>
        <id>jpql-report-only</id>
        <activation><activeByDefault>true</activeByDefault></activation>
        <properties>
            <jpql.check.failOnError>false</jpql.check.failOnError>
        </properties>
    </profile>
    <!-- CI: 오류 발견 시 빌드 실패 -->
    <profile>
        <id>jpql-strict</id>
        <properties>
            <jpql.check.failOnError>true</jpql.check.failOnError>
        </properties>
    </profile>
</profiles>
```

```xml
<!-- compilerArgs에서 프로퍼티 참조 -->
<arg>-Ajpql.check.failOnError=${jpql.check.failOnError}</arg>
```

```bash
# 평소: 리포트만 수집 (빌드 계속 진행)
mvn compile

# CI: 엄격 모드 (오류 발견 시 빌드 실패)
mvn compile -P jpql-strict
```

## 컴파일러 옵션 (어노테이션 프로세서 옵션)

아래 항목을 `-A` 형식으로 maven-compiler-plugin compilerArgs에 전달합니다.

| 옵션 키 | 기본값 | 설명 |
|---|---|---|
| jpql.check.reportDir | target/jpql-check | 리포트 출력 디렉터리 |
| jpql.check.formats | json | 출력 형식. 쉼표 구분으로 복수 지정 가능 (json, html, sarif) |
| jpql.check.failOnError | true | false면 ERROR를 컴파일러 WARNING으로 출력하여 컴파일을 중지하지 않음. 리포트에는 ERROR로 기록됨 |

## Maven 플러그인 설정 파라미터

| 파라미터 | 기본값 | 설명 |
|---|---|---|
| failOnError | true | ERROR 심각도 감지 시 빌드 실패 여부 |
| failOnWarning | false | WARNING 심각도 감지 시 빌드 실패 여부 |
| reportDirectory | target/jpql-check | 리포트 파일 읽을 경로 (컴파일러 옵션과 일치시킬 것) |
| reportFormats | json | 출력 형식 목록 |
| suppressUnresolved | false | 타입 미해석(UNRESOLVED) WARNING 억제 여부 |

## 리포트 출력 파일

- **report.json**: JSON 형식 전체 Finding 목록
- **report.html**: 브라우저에서 바로 볼 수 있는 HTML 테이블
- **results.sarif**: SARIF 2.1.0 형식 (IDE/CI 연동용)

각 Finding에는 다음이 포함됩니다: 규칙 ID, 심각도, 소스 파일·라인, 템플릿 문자열 원문, 문제 표현식·추론 타입, 기대 타입, 권장 우회 방법.

D-08(변수 경유 패턴)의 경우 템플릿 대입 라인과 비교 사용 라인이 모두 포함됩니다.

## Finding 예시

### 오류 1 예시

```
[D-01] MyRepository.java:[42,12] 등록 함수 abs의 인자 #0은 NUMERIC 카테고리 요구,
       STRING(java.lang.String) Path가 바인딩됨
  템플릿     : "ABS({0})"
  함수       : abs
  대상 표현식 : qProduct.name (java.lang.String)
  기대 타입   : NUMERIC
  권장 조치   : cast 적용 — "ABS(cast({0} as long))" 형식으로 우회 (PRD E-01)
```

### 오류 2 예시

```
[D-03] MyRepository.java:[87,8] 미등록 함수 date_format의 반환 타입이 Object로 추론되어
       Path 계열(qOrder.stringDttm)과의 비교에서 런타임 예외 발생
  템플릿     : "DATE_FORMAT({0}, {1})"
  함수       : date_format
  대상 표현식 : qOrder.stringDttm (java.lang.String)
  기대 타입   : java.lang.Object(미등록 함수 반환 추론)
  권장 조치   : function('DATE_FORMAT', ...) 표준 구문 또는 format({0} as 'yyyyMMdd') 으로 우회
```

## 우회 방법 요약

### 오류 1 우회 — cast 적용

```java
// 수정 전 (오류 발생)
Expressions.numberTemplate(Long.class, "ABS({0})", qEntity.stringColumn)

// 수정 후 (cast로 타입 명시)
Expressions.numberTemplate(Long.class, "ABS(cast({0} as long))", qEntity.stringColumn)
```

### 오류 2 우회 — function() 구문

```java
// 수정 전 (오류 발생)
Expressions.stringTemplate("DATE_FORMAT({0}, {1})", qEntity.column, "%Y%m%d")
    .goe(qEntity.stringDttm)

// 수정 후 방법 1: JPQL function() 구문
Expressions.stringTemplate("function('DATE_FORMAT', {0}, {1})", qEntity.column, "%Y%m%d")
    .goe(qEntity.stringDttm)

// 수정 후 방법 2: Hibernate 6 표준 format 함수 (DB 이식성 높음)
Expressions.stringTemplate("format({0} as 'yyyyMMdd')", qEntity.column)
    .goe(qEntity.stringDttm)
```

## 빌드 실행

```bash
# 전체 빌드 + 검사 실행
mvn compile

# 검사만 단독 실행
mvn jpql-check:check

# 리포트만 생성하고 빌드 실패는 무시
mvn compile -Dfail.on.error=false
```
