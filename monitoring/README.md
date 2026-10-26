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

## 사건 수집기 실행

`event-data`는 **상시 실행 프로세스 안의 스케줄러**다. 종목별 OpenDART는 15분, GDELT 검색어와 연합인포맥스 RSS 피드는 각각 5분마다 조회한다. 각 조회는 그 시점의 새 항목을 한꺼번에 처리한다. 현재 구현은 장외에도 같은 간격으로 조회한다. GDELT 전체 요청은 최소 10초 간격이며, 429가 오면 대기 시간을 늘린다.

로컬 `.env`의 `DART_API_KEY`와 `data/dev/event-dev.yml` 설정을 준비하고 JAR를 빌드한 뒤 실행한다. 감독 프로세스는 예기치 않게 종료된 수집기를 재시작한다.

```bash
./gradlew :event-data:bootJar
python3 monitoring/event_service.py start
python3 monitoring/event_service.py status
python3 monitoring/event_service.py stop
```

수집기 로그와 감독 상태는 `data/dev/events/runtime/`, API 조회 결과와 `health.json`은 `data/dev/events/`에 남는다. `health.json`에는 프로세스 ID, 마지막 상태 갱신, 제공처별 조회·성공·실패·429·새 사건 누계를 기록한다. 키는 이 파일에 넣지 않는다. `monitoring/native.py stop`은 Prometheus·Grafana만 멈추며 사건 수집기는 별도로 관리한다.

## 컨트롤타워

컨트롤타워는 별도 감독 프로세스로 실행한다. `python3 monitoring/control_service.py start|status|stop`으로 관리한다. 로컬 전용 화면은 토큰 없이 열린다. `monitoring/native.py`와 `event_service.py`의 시작·종료는 서로 독립적이다.

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

이 구성은 **2026-10-26 모의투자 테스트**의 로그·상태 파일 이름과 거래일을 사용한다. 다른 날짜에 실행할 때는 exporter의 `AUTOHELM_TEST_DATE`, Compose 설정, 수집기 설정을 함께 변경해야 한다. Grafana와 Prometheus의 포트는 Mac의 `127.0.0.1`에만 열린다. 대시보드는 로컬에서 익명 읽기 전용으로 제공한다.
