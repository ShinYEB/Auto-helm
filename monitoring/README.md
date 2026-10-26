# Market Data · Event Data 모니터링

읽기 전용 exporter가 `data/dev`의 시세 기록과 사건 수집기 상태 파일을 읽고, Prometheus가 15초마다 수집한다. Grafana는 `Auto-helm | Market Data`와 `Auto-helm | Event Data` 대시보드를 자동으로 불러온다. exporter에는 한투·OpenDART 키를 전달하지 않는다.

## Mac에서 직접 실행

```bash
brew install prometheus grafana
python3 monitoring/native.py start
python3 monitoring/native.py status
python3 monitoring/native.py stop
```

프로세스 상태와 로그는 `data/monitoring/native/`에 기록된다. Mac에서 현재 실행 중인 방식이다.

## 10/27~10/30 모의투자 수집 시험

Mac 로그인 세션의 `launchd`가 `collection_window.py`를 유지한다. 지정한 네 날짜에는 **07:30~20:30 KST**에 사건 수집기, 시세 WebSocket 수집기, Redis 봉 소비 예측기를 각각 별도 프로세스로 실행한다. 중간에 종료되면 10초 이상 간격을 두고 재시작한다. 시각이 지나면 세 프로세스를 정상 종료한다. 종목·시장별 1분봉은 실제 장 구간(KRX 정규장, NXT 프리·메인·애프터)에만 만든다. 이 네 날짜 이후에는 자동 기동하지 않는다. 범용 휴장 달력과 임시 시장 중단 대응은 후속 작업이다.

Redis, 컨트롤타워, Prometheus, Grafana는 별도 `launchd` 점검 작업이 1분마다 상태를 확인해 상시 유지한다. `.env`에는 `KIS_APP_KEY`, `KIS_APP_SECRET`, `DART_API_KEY`, `JEV_API_KEY`가 필요하다. 이 파일은 Git에서 제외하고 권한을 `0600`으로 둔다. 사건 검색용 OpenAI 키가 없으면 추가 웹 검색 단계는 비활성이다. 주문 서비스는 시작하지 않는다.

```bash
./gradlew :market-data:bootJar :event-data:bootJar :price-prediction:bootJar
python3 monitoring/collection_launchd.py install
python3 monitoring/collection_launchd.py status
python3 monitoring/collection_window.py status
python3 monitoring/collection_launchd.py uninstall
```

당일 상태는 `data/dev/market-day-YYYY-MM-DD.status.json`, 실행 로그는 `data/dev/market-day-YYYY-MM-DD.log` 및 `data/dev/collection-window/`에 남는다. 20:30 종료 뒤 `market-day-YYYY-MM-DD.report.json`에 KRX/NXT 수신 프레임, 봉 품질, 결손, 연결·파싱 오류, 재시작 및 자동 종료 여부를 기록한다. [Grafana 시세](http://127.0.0.1:3000/d/autohelm-market-day/auto-helm-market-data)와 [사건](http://127.0.0.1:3000/d/autohelm-event-data/auto-helm-event-data) 대시보드로 관찰한다. Mac을 전원에 연결하고 로그인 세션이 유지돼야 예약 작업이 동작한다.

복구 판단은 프로세스 종료 외에 하트비트·구독 확인·체결·봉·예측 출력 지연을 사용한다. 재시작 횟수에는 한도를 둔다. 오류·복구 이력은 `data/dev/operations/incidents/YYYY-MM-DD.jsonl`, 제공처 실패는 `data/dev/events/health-events/YYYY-MM-DD.jsonl`에 **추가만** 한다. 상태 JSON은 현재값으로 교체하지만 과거 오류 기록은 지우지 않는다. 마감 보고서는 한 번만 발행한다. 임계값과 검토 절차는 [복구·관측 LLD](../docs/lld/06-recovery-observability.md)에 정리했다.

## 사건 수집기 단독 실행

`event-data`는 실행 중 내부 스케줄러로 종목별 OpenDART를 15분, GDELT 검색어와 연합인포맥스 RSS 피드를 각각 5분마다 조회한다. 10/27~30 시험에서는 위 감독 프로세스가 실행 시간을 제한한다. GDELT 전체 요청은 최소 10초 간격이며, 429가 오면 대기 시간을 늘린다.

아래 명령은 예약 시험을 사용하지 않을 때 단독으로 실행하는 방법이다. 예약 시험과 동시에 시작하지 않는다. 로컬 `.env`와 `data/dev/event-dev.yml`을 준비한다.

```bash
./gradlew :event-data:bootJar
python3 monitoring/event_service.py start
python3 monitoring/event_service.py status
python3 monitoring/event_service.py stop
```

수집기 로그와 감독 상태는 `data/dev/events/runtime/`, API 조회 결과와 `health.json`은 `data/dev/events/`에 남는다. `health.json`에는 프로세스 ID, 마지막 상태 갱신, 제공처별 조회·성공·실패·429·새 사건 누계를 기록한다. 키는 이 파일에 넣지 않는다. `monitoring/native.py stop`은 Prometheus·Grafana만 멈추며 사건 수집기는 별도로 관리한다.

## 컨트롤타워

컨트롤타워는 별도 감독 프로세스로 실행한다. 수집 시험의 설치 명령은 해당 감독 프로세스도 `launchd`에 등록한다. 로컬 전용 화면은 토큰 없이 열린다. `monitoring/native.py`와 `event_service.py`의 시작·종료는 서로 독립적이다.

## Docker Compose

Docker Desktop에서 새 컨테이너를 시작할 수 있을 때 사용할 수 있다. Mac에서 직접 실행 중인 Prometheus·Grafana와 포트가 겹치므로 먼저 `python3 monitoring/native.py stop`을 실행한다.

```bash
docker compose -f monitoring/compose.yaml up -d
```

- Grafana: <http://localhost:3000/d/autohelm-market-day/auto-helm-market-data>
- Event Data: <http://localhost:3000/d/autohelm-event-data/auto-helm-event-data>
- Prometheus: <http://localhost:9090/targets>
- 종료: `docker compose -f monitoring/compose.yaml down`

대시보드에는 수집기 상태·재시작 횟수, KRX/NXT 수신 지연과 초당 수신량, 1분봉 품질, 결손·오류 누적이 표시된다. 수신 지연은 해당 장이 열려 있을 때만 평가한다. 수집기 상태는 감독 프로세스가 쓰는 상태 파일의 갱신 시각을 확인한다.

Event Data 대시보드에는 서비스 실행 여부, DART 마지막 성공 시각, 제공처별 조회·실패·429·새 사건 건수가 표시된다. exporter는 RSS 피드별 최신 항목 수·겹친 항목 수·새 항목 수·누락 의심 횟수도 제공한다. `GDELT`가 연결 시간 초과나 429로 실패하면 DART 수집은 계속되며 각각의 실패 건수가 분리된다.

Mac 네이티브 exporter는 한국 시간의 당일 파일로 자정에 자동 전환한다. `AUTOHELM_TEST_DATE`를 설정하면 특정 날짜에 고정할 수 있다. Compose로 실행하는 경우에는 해당 설정을 별도로 확인한다. Grafana와 Prometheus의 포트는 Mac의 `127.0.0.1`에만 열린다. 대시보드는 로컬에서 익명 읽기 전용으로 제공한다.
