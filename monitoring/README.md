# Market Data 모니터링

현재 실행 중인 시세 수집기를 재시작하지 않고 관찰한다. 읽기 전용 exporter가 `data/dev`의 상태 파일과 JSONL을 읽고, Prometheus가 15초마다 수집한다. Grafana는 `Auto-helm | Market Data` 대시보드를 자동으로 불러온다. 한투 키는 이 구성에 전달하지 않는다.

## Mac에서 직접 실행

```bash
brew install prometheus grafana
python3 monitoring/native.py start
python3 monitoring/native.py status
python3 monitoring/native.py stop
```

프로세스 상태와 로그는 `data/monitoring/native/`에 기록된다. Mac에서 현재 실행 중인 방식이다.

## Docker Compose

Docker Desktop에서 새 컨테이너를 시작할 수 있을 때 사용할 수 있다. Mac에서 직접 실행 중인 Prometheus·Grafana와 포트가 겹치므로 먼저 `python3 monitoring/native.py stop`을 실행한다.

```bash
docker compose -f monitoring/compose.yaml up -d
```

- Grafana: <http://localhost:3000/d/autohelm-market-day/auto-helm-market-data>
- Prometheus: <http://localhost:9090/targets>
- 종료: `docker compose -f monitoring/compose.yaml down`

대시보드에는 수집기 상태·재시작 횟수, KRX/NXT 수신 지연과 초당 수신량, 1분봉 품질, 결손·오류 누적이 표시된다. 수신 지연은 해당 장이 열려 있을 때만 평가한다. 수집기 상태는 감독 프로세스가 쓰는 상태 파일의 갱신 시각을 확인한다.

이 구성은 **2026-10-26 모의투자 테스트**의 로그·상태 파일 이름과 거래일을 사용한다. 다른 날짜에 실행할 때는 exporter의 `AUTOHELM_TEST_DATE`, Compose 설정, 수집기 설정을 함께 변경해야 한다. Grafana와 Prometheus의 포트는 Mac의 `127.0.0.1`에만 열린다. 대시보드는 로컬에서 익명 읽기 전용으로 제공한다.
