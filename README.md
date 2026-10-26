# Auto-helm

국내 주식의 매매 판단과 결과 검증을 위한 개인 프로젝트다. Gradle 멀티모듈에서 두 개의 Spring Boot 서비스를 독립 실행한다. `market-data`는 [Market Data LLD](docs/lld/01-market-data.md)의 관찰용 시세 수집을, `event-data`는 [Event Data LLD](docs/lld/03-event-data.md)의 공시·뉴스 후보 수집을 담당한다. 주문, 가격 예측, AI 사건 분석, 모바일 API는 아직 연결하지 않았다.

| 모듈 | 실행 단위 | 현재 책임 |
| --- | --- | --- |
| `market-data` | 독립 JAR·프로세스 | 한투 WebSocket 수신, KRX/NXT 1분봉, 로컬 기록 |
| `event-data` | 독립 JAR·프로세스 | OpenDART 공시 원본, GDELT 뉴스 메타데이터 수집·중복 제거·로컬 기록 |

두 서비스는 서로의 내부 클래스나 메모리를 호출하지 않는다. 파일도 `data/<환경>/market/`과 `data/<환경>/events/`로 나눈다. 판단기와 연결할 서비스 간 계약은 소비자가 구현될 때 명시적으로 추가한다. 현재 단계에서 모듈 분리는 **실행·장애 경계**이며, 서비스 간 메시지 전달은 아직 구현하지 않았다.

## 실행 환경

- Java 21
- Kotlin 2.0.21, Spring Boot 3.5.16, Gradle 8.9 Wrapper
- 한국투자증권 Open API의 WebSocket 접속 정보(실제 시세 수신 시)

```bash
./gradlew test
./gradlew :market-data:bootRun
./gradlew :event-data:bootRun
```

기본값은 `market.enabled=false`, `events.enabled=false`다. 이 상태에서는 두 앱의 기동만 확인하고 외부 API에 접속하지 않는다. 두 실행 앱은 각각 별도 프로세스나 파드로 배포한다.

## 한투 시세 수신 설정

접속키는 환경 변수 `KIS_APP_KEY`, `KIS_APP_SECRET`으로 넣는다. 다른 설정은 저장소 밖의 YAML 파일로 준비해 실행한다. 다음 예시의 날짜와 장 구간은 **실제 거래일·시장 일정을 확인해 채워야 한다.** 확인되지 않은 구간의 체결은 1분봉으로 쓰지 않는다.

```yaml
market:
  enabled: true
  environment: dev
  data-dir: ./data
  symbols: ["005930"]
  rest-url: https://openapivts.koreainvestment.com:29443
  ws-url: ws://ops.koreainvestment.com:31000
  sessions:
    - venue: KRX
      name: regular
      dates: ["2026-10-26"]
      start: "09:00:00"
      end: "15:30:00"
    # NXT는 해당 종목의 실제 거래 가능 날짜·장 구간을 확인한 뒤 추가한다.
```

```bash
export KIS_APP_KEY='...'
export KIS_APP_SECRET='...'
./gradlew :market-data:bootRun --args='--spring.config.additional-location=file:/absolute/path/market-dev.yml'
```

실전 환경은 `environment: prd`로 바꾸고 실전 키, 실전 REST/WebSocket 주소를 함께 설정해야 한다. 프로그램은 환경과 접속 주소가 맞지 않으면 시작하지 않는다. **현재 코드에는 주문 기능이 없다.** 실제 계좌의 시세 채널 사용 가능 여부와 메시지 필드는 계좌에서 연결 시험을 해야 한다.

## 사건 수집 설정

`event-data`는 시세 수집과 독립적으로 기동한다. 아래는 GDELT 메타데이터 실험 예시다. 검색어는 종목코드에 연결한다. **같은 종목은 3분마다**, 모든 GDELT 요청은 최소 10초 간격으로 보낸다. 429가 오면 `Retry-After` 또는 지수 대기로 멈춘다.

```yaml
events:
  enabled: true
  environment: dev
  data-dir: ./data
  gdelt:
    enabled: true
    searches:
      "005930": "삼성전자"
  dart:
    enabled: false
```

```bash
./gradlew :event-data:bootRun --args='--spring.config.additional-location=file:/absolute/path/events-dev.yml'
```

OpenDART를 사용하려면 `DART_API_KEY` 환경 변수와 종목코드별 8자리 기업 고유번호를 설정한다. `dart.enabled=true`일 때 설정 예시는 다음과 같다. 서비스는 새 접수번호의 원본 ZIP을 받고, 제목·접수번호·최초 발견 시각을 기록한다.

```yaml
events:
  enabled: true
  environment: dev
  dart:
    enabled: true
    corp-codes:
      "005930": "00126380"
```

GDELT URL을 따라가 언론사 기사 본문을 받지 않는다. 수집 결과를 LLM이나 매매 판단에 전달하는 코드는 아직 없다. 네이버 검색 결과와 뉴스토어 일반 상품은 AI 분석 경로에 연결하지 않는다. 데이터 이용 범위와 제공처 선택 근거는 [ADR-0001](docs/adr/0001-event-data-sources.md)에 기록했다.

로컬 Mac에서 계속 실행하려면 `.env`에 `DART_API_KEY`를 넣고, `data/dev/event-dev.yml`에 조회할 종목을 설정한 뒤 [사건 수집기 실행 안내](monitoring/README.md#사건-수집기-실행)에 따라 감독 프로세스를 시작한다. `.env`는 Git에서 제외하며 파일 권한을 제한한다.

## 저장 데이터

`market-data`는 `data/<환경>/market/` 아래에 거래일별 JSONL 파일을 만든다.

- `raw/`: 한투 체결 데이터 원본
- `bars/`: 확정된 1분봉
- `gaps/`: 연결 중단과 데이터 검증 실패 구간

원본 기록은 1분봉 처리 전에 디스크에 반영한다. 재시작하면 해당 날짜의 원본과 확정 봉을 읽고, 이미 확정된 봉은 다시 기록하지 않는다. 이 파일을 Git에 올리지 않도록 `data/`를 제외했다. 장후 Parquet 변환과 AWS DynamoDB 요약 게시는 후속 단계다.

`event-data`는 `data/<환경>/events/` 아래에 조회 응답(`raw/`), 정규화한 사건(`events/`), 사건과 종목 연결(`links/`), 공시 ZIP(`disclosures/`)을 저장한다. 같은 접수번호·URL을 다시 발견하면 사건 기록을 중복 추가하지 않는다. 서비스마다 자기 파일만 쓰며, `dev`와 `prd`의 경로를 분리한다.

## 현재 검증 범위

재생 데이터 테스트에서 KRX·NXT 봉 분리, 체결이 없는 분, 연결 중단, 잘못된 메시지, 재시작 후 중복 기록 방지를 확인한다. 2026-10-26에는 한투 **모의투자** WebSocket에서 `005930`의 KRX·NXT 체결 수신과 `READY` 1분봉 확정을 확인했다. 사건 수집은 로컬 HTTP 제공처를 사용한 페이지·메타데이터 파싱, 기록·재시작 중복 방지 테스트까지 확인했다. OpenDART 실제 키를 통한 라이브 수집과 GDELT 3분 연속 수집의 지연·429 특성은 아직 확인하지 않았다. 주문 기능과 실전 계좌 연결도 검증하지 않았다.

## 모니터링

오늘의 모의 시세 수집 테스트는 [Prometheus·Grafana 구성](monitoring/README.md)으로 관찰할 수 있다. 모니터링 프로세스는 수집 중인 JSONL과 상태 파일을 읽으며 한투 키를 사용하지 않는다.
