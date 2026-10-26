# Auto-helm

국내 주식의 매매 판단과 결과 검증을 위한 개인 프로젝트다. 현재 코드는 [Market Data LLD](docs/lld/01-market-data.md)의 **관찰용 시세 수집 경로**를 구현한다. 주문, 가격 예측, 뉴스 분석, 모바일 API는 아직 연결하지 않았다.

## 실행 환경

- Java 21
- Kotlin 2.0.21, Spring Boot 3.5.16, Gradle 8.9 Wrapper
- 한국투자증권 Open API의 WebSocket 접속 정보(실제 시세 수신 시)

```bash
./gradlew test
./gradlew bootRun
```

기본값은 `market.enabled=false`다. 이 상태에서는 Spring 애플리케이션의 기동만 확인하고 한투에 접속하지 않는다.

## 한투 시세 수신 설정

접속키는 환경 변수 `KIS_APP_KEY`, `KIS_APP_SECRET`으로 넣는다. 다른 설정은 저장소 밖의 YAML 파일로 준비해 실행한다. 다음 예시의 날짜와 장 구간은 **실제 거래일·시장 일정을 확인해 채워야 한다.** 확인되지 않은 구간의 체결은 1분봉으로 쓰지 않는다.

```yaml
market:
  enabled: true
  environment: dev
  data-dir: ./data
  symbols: ["005930"]
  rest-url: https://openapivts.koreainvestment.com:29443
  ws-url: ws://ops.koreainvestment.com:31000/tryitout
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
./gradlew bootRun --args='--spring.config.additional-location=file:/absolute/path/market-dev.yml'
```

실전 환경은 `environment: prd`로 바꾸고 실전 키, 실전 REST/WebSocket 주소를 함께 설정해야 한다. 프로그램은 환경과 접속 주소가 맞지 않으면 시작하지 않는다. **현재 코드에는 주문 기능이 없다.** 실제 계좌의 시세 채널 사용 가능 여부와 메시지 필드는 계좌에서 연결 시험을 해야 한다.

## 저장 데이터

`data/<환경>/market/` 아래에 거래일별 JSONL 파일을 만든다.

- `raw/`: 한투 체결 데이터 원본
- `bars/`: 확정된 1분봉
- `gaps/`: 연결 중단과 데이터 검증 실패 구간

원본 기록은 1분봉 처리 전에 디스크에 반영한다. 재시작하면 해당 날짜의 원본과 확정 봉을 읽고, 이미 확정된 봉은 다시 기록하지 않는다. 이 파일을 Git에 올리지 않도록 `data/`를 제외했다. 장후 Parquet 변환과 AWS DynamoDB 요약 게시는 후속 단계다.

## 현재 검증 범위

재생 데이터 테스트에서 KRX·NXT 봉 분리, 체결이 없는 분, 연결 중단, 잘못된 메시지, 재시작 후 중복 기록 방지를 확인한다. 한투 실계정 WebSocket 연결은 접속키가 없어 아직 검증하지 않았다.
