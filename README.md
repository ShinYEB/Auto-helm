# Auto-helm

국내 주식의 매매 판단과 결과 검증을 위한 개인 프로젝트다. Gradle 멀티모듈에서 세 개의 데이터 서비스, 학습 배치, 운영용 컨트롤타워를 독립 실행한다. `market-data`는 [Market Data LLD](docs/lld/01-market-data.md)의 관찰용 시세 수집을, `event-data`는 [Event Data LLD](docs/lld/03-event-data.md)의 공시·뉴스 후보 수집을 담당한다. `price-prediction`은 Redis Stream에서 봉을 받아 기준선 또는 승인된 경량 모델의 예측을 기록한다. 주문과 모바일 API는 아직 연결하지 않았다. Jev 점수 작업자는 로컬 dev 설정에서 활성화했고 실제 API 시험 요청에 성공했다. 임계치 초과 웹 검색 작업자는 API 키가 없어 비활성이다.

| 모듈 | 실행 단위 | 현재 책임 |
| --- | --- | --- |
| `market-data` | 독립 JAR·프로세스 | 한투 WebSocket 수신, KRX/NXT 1분봉, 로컬 기록 |
| `event-data` | 독립 JAR·프로세스 | OpenDART 공시, GDELT·연합인포맥스 RSS 수집, 사건·분석 파일 기록과 Redis 투영 |
| `price-prediction` | 독립 JAR·프로세스 | 확정 봉 Stream 소비, 1분·1시간 직전 종가 기준선과 사용 불가 상태 기록 |
| `model-training` | 실행 후 종료되는 배치 JAR | 한투 과거 분봉 조회·원본 기록, 경량 모델 학습, 명시적 모델 승격 |
| `model-contract` | 라이브러리 | 모델 묶음의 형식·해시 검증·적재 |
| `control-tower` | 독립 웹 서버·프로세스 | 로컬 웹 콘솔, 상태 집계, 인증된 dev 학습 배치 제어 |

`model-training`은 가중치, 단일 특징 `logReturn1m`, 목표 정의, 데이터 해시, 시간순 검증 오차가 포함된 `ModelBundle.v1` 후보를 만든다. 운영 중인 `price-prediction`은 환경별로 승인된 묶음만 시작할 때 읽는다. 묶음이 없으면 기존 직전 종가 기준선을 사용한다. 첫 모델은 작은 선형 수익률 모델이며 상태 기반 신경망, 주간 거래 성과 평가와 자동 재학습은 아직 구현되지 않았다.

상주 서비스와 학습 배치는 서로의 메모리를 호출하지 않는다. 파일도 `data/<환경>/market/`, `data/<환경>/events/`, `data/<환경>/prediction/`, `data/<환경>/historical/`로 나눈다. `journal-bridge`는 시세·사건 서비스가 공유하는 JSONL 완성 행 읽기와 체크포인트 코드이며 실행 프로세스가 아니다.

확정 1분봉은 파일 기록 후 별도 작업자가 Redis Stream `autohelm:<환경>:market:minute-bars:v1`에 발행하고, 예측기가 소비자 그룹에서 받는다. 사건과 종목 연결은 별도 작업자가 [Redis 조회 캐시](docs/lld/03-event-data.md#6-파일과-redis-계약)에 투영한다. 세 경로 모두 기본값은 꺼져 있다. AI 분석 결과 캐시는 구현했고 설정을 켜면 사용한다. 파일 기록은 원본으로 유지한다.

## 실행 환경

- Java 21
- Kotlin 2.0.21, Spring Boot 3.5.16, Gradle 8.9 Wrapper
- 한국투자증권 Open API의 WebSocket 접속 정보(실제 시세 수신 시)
- Redis(선택; Stream 발행 또는 사건 캐시를 켤 때)

```bash
./gradlew test
./gradlew :market-data:bootRun
./gradlew :event-data:bootRun
./gradlew :price-prediction:bootRun
```

기본값은 `market.enabled=false`, `events.enabled=false`, `prediction.enabled=false`다. 이 상태에서는 앱의 기동만 확인하고 외부 API에 접속하지 않는다. 세 실행 앱은 각각 별도 프로세스나 파드로 배포한다.

### 로컬 웹 콘솔

콘솔은 컨트롤타워가 제공하며 브라우저는 다른 서비스와 직접 통신하지 않는다. 기본 접속 주소는 <http://127.0.0.1:8088/>이다. 로컬 전용 화면으로, 토큰 입력 없이 열린다. 학습 배치를 화면에서 실행하려면 먼저 `./gradlew :model-training:bootJar`로 JAR를 만들고, 과거 시세 수집에는 `KIS_TRAINING_APP_KEY`와 `KIS_TRAINING_APP_SECRET`도 컨트롤타워 프로세스에 전달한다.

```bash
./gradlew :model-training:bootJar :control-tower:bootJar
./gradlew :control-tower:bootRun
```

Mac에서 백그라운드로 유지하려면 `python3 monitoring/control_service.py start`를 실행한다. `status`와 `stop` 명령으로 상태 확인과 종료를 할 수 있다. 감독 프로세스가 비정상 종료된 컨트롤타워를 다시 시작하며, 로그는 `data/dev/control/runtime/`에 남는다.

컨트롤타워는 기본적으로 로컬에서만 수신한다. 화면에는 Grafana의 시세·사건 핵심 지표를 상태 카드와 1·6·24시간 차트로 표시한다. 컨트롤타워만 로컬 Prometheus(`127.0.0.1:9090`)에 접속하며, Prometheus가 중단되면 대시보드에 사용 불가 상태를 표시한다. 화면에서 dev 종목 관리, 과거 분봉 수집·모델 학습·dev 승격, 후보·활성 모델과 수집 정보 조회가 가능하다. 종목 설정은 환경별 파일에 저장되고 새 버전 수집기가 주기적으로 반영한다. prd 승격과 주문은 제공하지 않는다. `GET /api/snapshot`의 시세·예측 상태는 출력 기록을 통한 관측이며 프로세스 생존 확인과 구별된다. [컨트롤타워 설계](docs/lld/05-control-tower.md)에 API와 장애 동작을 기록했다.

### Redis 연결

개발용 Redis 예시는 `docker compose -f infra/redis/compose.yaml up -d`로 띄운다. Docker를 사용할 수 없으면 로컬 Redis를 설치하고 `mkdir -p data/dev/redis && redis-server --bind 127.0.0.1 --port 16379 --appendonly yes --dir ./data/dev/redis`로 실행할 수 있다. 두 방법 모두 로컬의 `127.0.0.1:16379`에만 열고 AOF를 사용한다. 각 앱의 별도 설정 파일에서 다음을 추가한다.

```yaml
spring:
  data:
    redis:
      host: 127.0.0.1
      port: 16379
market:
  redis:
    enabled: true
events:
  redis:
    enabled: true
```

각 서비스에는 해당 서비스의 설정 블록만 넣는다. `dev`와 `prd`는 Redis 인스턴스와 인증 정보까지 분리한다. 발행·투영 작업은 완성된 파일 행만 읽고, Redis 성공 후 파일 체크포인트를 전진시킨다. Redis가 비어 있으면 파일에서 재생성한다. 재생 과정에서 Stream 항목이 중복될 수 있으므로 예측 소비자는 봉 ID로 중복을 제거해야 한다. 캐시가 준비되지 않았거나 투영기가 15초 넘게 응답하지 않으면 사건 조회는 `available=false`를 반환한다. 현재 프로젝트에는 이를 외부에 제공하는 API가 없다.

예측기는 별도 설정 파일에서 `prediction.enabled=true`, 동일한 환경 이름·Redis 주소, 확인된 거래일의 시장별 장 구간을 설정한다. 예를 들면 다음과 같다.

```yaml
spring:
  data:
    redis:
      host: 127.0.0.1
      port: 16379
prediction:
  enabled: true
  environment: dev
  data-dir: ./data
  sessions:
    - venue: KRX
      name: regular
      dates: ["2026-10-26"]
      start: "09:00:00"
      end: "15:30:00"
```

```bash
./gradlew :price-prediction:bootRun --args='--spring.config.additional-location=file:/absolute/path/prediction-dev.yml'
```

예측기는 `READY` 봉에 대해 같은 장 구간 안의 다음 1분과 정확히 60분 뒤 종가를 직전 종가로 예측한다. 지연·결손·장 종료는 사용 불가 상태로 기록한다. KRX 공식 종가와 NXT 종료 가격은 출처와 확정 규칙이 정해질 때까지 `ACTUAL_DEFINITION_UNCONFIRMED`로 기록하며 가격을 만들지 않는다. 이 기준선은 매매 판단에 연결되지 않는다.

### 과거 분봉 수집과 모델 배치

`model-training`은 요청할 때만 실행하고 종료한다. 다음 예시는 **모의 환경의 읽기 전용 조회**다. 날짜·장 구간과 KRX/NXT 지원 범위를 계좌에서 먼저 확인한다. 학습에 쓰는 과거 봉은 실시간 `READY` 봉과 출처를 구분해 저장한다. 인증 정보는 저장소 밖의 환경 변수로만 전달한다.

```bash
export KIS_TRAINING_APP_KEY='...'
export KIS_TRAINING_APP_SECRET='...'
./gradlew :model-training:bootRun --args='--training.command=collect --training.environment=dev --training.symbol=005930 --training.venue=KRX --training.session=regular --training.from=2026-10-20 --training.to=2026-10-24 --training.start-time=09:00:00 --training.end-time=15:30:00'
```

배치는 한투의 `주식일별분봉조회` 응답을 날짜·시각으로 페이지 순회하며, 응답 원본과 정규화한 JSONL 봉, 완료 명세를 `data/dev/historical/`에 기록한다. 완료 명세와 체크섬이 맞는 날짜는 재요청하지 않는다. 날짜 중간에 실패하면 그 날짜의 원본 페이지는 남지만 다음 실행에서 **해당 날짜를 처음부터 다시 조회**한다. 제공처 접근 권한·과거 조회 범위·실제 페이지 크기는 계좌별 읽기 전용 시험이 필요하다. [한투 공식 조회 예제](https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py)를 기준으로 구현했고, 아직 실제 계좌 응답으로 검증하지 않았다.

수집한 기간에 완료된 거래일이 3일 이상 있으면 후보를 학습한다. 마지막 20% 거래일을 검증에 쓰고, 같은 표본의 직전 종가 기준선 대비 **1분·1시간 가격 MAE가 각각 0.5% 이상 작을 때만** 승격 가능한 후보로 표시한다. 이 기준은 초기 기술 검증용이며 실전 수익 기준이 아니다.

```bash
./gradlew :model-training:bootRun --args='--training.command=fit --training.symbol=005930 --training.venue=KRX --training.session=regular --training.from=2026-10-20 --training.to=2026-10-24'
./gradlew :model-training:bootRun --args='--training.command=promote --training.version=<후보-버전> --training.target-environment=dev'
```

후보는 `data/dev/training/bundles/<버전>/`에 생성된다. 승격 명령은 적격 후보를 `data/dev/models/bundles/`로 복사하고 `active.json`을 원자적으로 교체한다. 예측기를 통제된 시점에 재시작하면 묶음의 형식·해시를 검증해 적재한다. 적재 오류가 있으면 소비자를 시작하지 않는다. prd 승격은 dev에서 활성화된 **동일 해시**와 별도의 검증 증거 파일(`bundleSha256`, `devReplayPassed`, `mockTradingPassed`, `approvedAt`, `note`)이 필요하다. 그 증거를 실제 검증 없이 만들거나 prd 승격을 자동 실행하지 않는다.

현재 테스트는 모의 HTTP 응답과 합성 거래일을 사용한다. 모델의 실제 시장 성능, 주간 피드백, 주문 성과와 비용은 아직 검증되지 않았다.

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

`event-data`는 시세 수집과 독립적으로 기동한다. 아래는 RSS와 GDELT 수집 예시다. GDELT 검색어는 종목코드에 연결한다. RSS 피드와 GDELT 검색어는 각각 **5분마다** 조회하며, 모든 GDELT 요청은 최소 10초 간격으로 보낸다. 429가 오면 `Retry-After` 또는 지수 대기로 멈춘다.

```yaml
events:
  enabled: true
  environment: dev
  data-dir: ./data
  rss:
    enabled: true
    poll-interval: 5m
    # 기본값: 증권, IB/기업, 정책/금융, 채권/외환, 국제뉴스
  gdelt:
    enabled: true
    searches:
      "005930": '"Samsung Electronics"'
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

GDELT URL을 따라가 언론사 기사 본문을 받지 않는다. 연합인포맥스 RSS의 다섯 피드는 각각 5분마다 조회하며, 짧은 설명은 Jev 작업용 임시 파일에만 둔다. NAVER 뉴스 검색은 [NAVER API HUB](https://api.ncloud-docs.com/docs/naver-api-hub-search-news)에서 발급한 `NAVER_API_HUB_CLIENT_ID`, `NAVER_API_HUB_CLIENT_SECRET`을 `.env`에 넣고 `events.naver.enabled=true`로 켜는 선택 경로다. 관심 종목명별 조회이며 일 500회 내부 한도를 둔다. 네이버 개발자센터의 신규 검색 API 키는 발급되지 않으므로 API HUB 키가 필요하다. 공시는 일부 주요사항 유형의 접수번호 일치 핵심 수치를 Jev 입력에 추가하고, 그 밖의 유형은 제목만 사용한다. Jev 분석은 `events.jev.enabled=true`와 `JEV_API_KEY`가 있어야 실행된다. 임계치 초과 웹 검색·LLM 확인은 `events.search.enabled=true`와 `OPENAI_API_KEY`가 있어야 실행된다. 점수의 매매 판단 연결과 실제 API 품질 검증은 아직 남아 있다. 출처와 이용 범위는 [ADR-0002](docs/adr/0002-event-issue-scoring.md)에 기록했다. 두 모델 작업자는 각각 별도 스레드에서 실행되며 기본적으로 비활성이다. 키를 설정하더라도 각 `enabled` 설정을 켜기 전에는 유료 API 호출을 하지 않는다.

로컬 Mac에서 계속 실행하려면 `.env`에 `DART_API_KEY`를 넣고, `data/dev/event-dev.yml`에 조회할 종목을 설정한 뒤 [사건 수집기 실행 안내](monitoring/README.md#사건-수집기-실행)에 따라 감독 프로세스를 시작한다. `.env`는 Git에서 제외하며 파일 권한을 제한한다.

## 저장 데이터

`market-data`는 `data/<환경>/market/` 아래에 거래일별 JSONL 파일을 만든다.

2026-10-27~30 모의투자 시험은 Mac `launchd` 감독 프로세스가 매일 07:30~20:30 KST에 시세·사건·예측기를 함께 기동·종료한다. Redis·컨트롤타워·모니터링은 상시 유지한다. 복구 판단은 프로세스 종료와 하트비트·구독·봉·예측 출력 지연을 사용하고, 오류·복구 사건은 추가 전용 기록으로 보존한다. 네 날짜 이외의 거래일 달력과 휴장·임시 중단 처리는 아직 구현 전이다. [실행·상태·일일 보고서 안내](monitoring/README.md#10271030-모의투자-수집-시험)

- `raw/`: 한투 체결 데이터 원본
- `bars/`: 확정된 1분봉
- `gaps/`: 연결 중단과 데이터 검증 실패 구간

원본 기록은 1분봉 처리 전에 디스크에 반영한다. 재시작하면 해당 날짜의 원본과 확정 봉을 읽고, 이미 확정된 봉은 다시 기록하지 않는다. 이 파일을 Git에 올리지 않도록 `data/`를 제외했다. 장후 Parquet 변환과 AWS DynamoDB 요약 게시는 후속 단계다.

`event-data`는 `data/<환경>/events/` 아래에 조회 응답(`raw/`), 정규화한 사건(`events/`), 사건과 종목 연결(`links/`), 공시 ZIP(`disclosures/`)을 저장한다. 같은 접수번호·URL을 다시 발견하면 사건 기록을 중복 추가하지 않는다. 서비스마다 자기 파일만 쓰며, `dev`와 `prd`의 경로를 분리한다.

## 현재 검증 범위

재생 데이터 테스트에서 KRX·NXT 봉 분리, 체결이 없는 분, 연결 중단, 잘못된 메시지, 재시작 후 중복 기록 방지를 확인한다. 2026-10-26에는 한투 **모의투자** WebSocket에서 `005930`의 KRX·NXT 체결 수신과 `READY` 1분봉 확정을 확인했다. 사건 수집은 로컬 HTTP 제공처를 사용한 페이지·메타데이터 파싱, 기록·재시작 중복 방지 테스트를 통과했다. 같은 날 OpenDART 실제 키로 공시 사건 1건과 원본 ZIP 1개를 기록했고, GDELT 연속 조회에서는 제한 응답과 오류가 관측됐다. GDELT의 국내 뉴스 포괄성·발견 지연은 아직 검증하지 않았다. 주문 기능과 실전 계좌 연결도 검증하지 않았다.

Redis 연결은 별도 임시 포트와 합성 데이터로 봉 발행 → 소비자 그룹 → 기준선 기록, 사건 캐시 투영, Redis 메모리 초기화 후 파일 재발행·캐시 재생성, 중복 예측 기록 방지를 확인했다. 기존 라이브 수집기의 설정이나 프로세스는 변경하지 않았다.

## 모니터링

모의 시세 수집 시험은 [Prometheus·Grafana 구성](monitoring/README.md)으로 관찰할 수 있다. 모니터링 프로세스는 당일 JSONL과 상태 파일을 읽으며 한투 키를 사용하지 않는다.
# 종목 검색 카탈로그

컨트롤타워의 **종목 관리** 탭은 시가총액 상위 1,000개 기업을 기업명이나 종목코드로 검색합니다. 검색 결과를 선택하면 종목코드와 OpenDART 기업 고유번호가 등록 양식에 입력됩니다. 등록한 종목을 선택하면 시세 봉, 과거 데이터 확보 현황, 활성 모델, 학습 후보와 작업, 최근 사건을 볼 수 있습니다.

카탈로그는 환경별 `data/<environment>/control/catalog.sqlite`에 저장합니다. [일별 시가총액 CSV](https://aikstockdata.com/downloads)와 [OpenDART 기업 고유번호](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019018)를 결합하며, DART 코드가 없는 우선주 등은 제외합니다. `DART_API_KEY`를 환경 변수로 설정한 다음 `python3 monitoring/import_stock_catalog.py --output data/dev/control/catalog.sqlite`를 실행하세요. 시가총액 자료의 기준일은 화면에 표시됩니다. 이 자료는 검색 편의용이며 매매 판단에 사용하지 않습니다. 갱신은 장 종료 후 필요할 때 실행합니다.
