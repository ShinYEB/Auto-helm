# 안드로이드 원격 모니터링

안드로이드 앱은 로컬 Mac의 `dev` 모의투자와 `prd` 실전투자 상태를 **조회 전용**으로 본다. Mac 서버는 확정된 스냅샷과 중요한 이벤트를 환경별 AWS DynamoDB에 비동기로 게시한다. 앱은 `HTTPS → API Gateway HTTP API → 조회 Lambda → DynamoDB` 경로로 읽는다. Lambda는 앱의 요청에 따라 실행하며, 이 조회 경로에서 Mac 서버에 직접 접속하지 않는다. 원격 화면은 주문 승인·주문 전송·매매 판단의 입력 경로가 아니다. [AWS HTTP API와 Lambda 연동](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api.html).

```text
Mac dev  ── 로컬 JSONL → monitor outbox ──→ AWS monitor_state_dev / monitor_events_dev
         └─ DynamoDB Local: 개발·검색 인덱스
Mac prd  ── 로컬 JSONL → monitor outbox ──→ AWS monitor_state_prd / monitor_events_prd
         └─ AWS app_events_prd: 기존 사건·판정 인덱스
Android ── HTTPS + 사용자 인증 ──→ API Gateway ──→ 조회 Lambda ──→ 허용된 dev/prd 모니터링 테이블
```

## 게시 시점과 기록 경계

| 게시 종류 | 초기 기준 | 내용 |
| --- | --- | --- |
| 종목 스냅샷 | 완성된 1분 봉과 판정이 확정될 때, 종목당 최대 1분 주기 | 현재 KRX·NXT 단계와 가격, 네 예측값·유효성·모델 버전, 최신 사건 신호, 최종 판단, 데이터 시각 |
| 계좌·대시보드 스냅샷 | 계좌 재조회·주문 상태 변경 때와 1분 주기 | 현금·평가금액·포지션 요약, 미체결 개수, 위험 한도 상태, 최종 `release_id` |
| 상태 heartbeat | 30초 주기 | Mac 수집·분석·주문 실행기의 상태, 마지막 봉·계좌 확인 시각, 큐 지연, 주문 중지 여부 |
| 사건 이벤트 | 새 공시·뉴스 분석, 판단 변경, 주문 접수·거부·부분 체결·체결·취소, 장애·복구가 확정될 때 | `event_id`, 유형, 종목, 발생·확정 시각, 요약, 관련 결정·주문 ID |

주문 이벤트는 한투의 접수·체결 상태를 확인한 뒤 게시한다. 판단 후보와 실제 주문 상태를 화면에서 다른 종류로 표시한다. 계좌번호 전체, KIS 키·토큰, 원문 기사·공시, 상세 주문 요청 본문은 AWS 모니터링 테이블에 넣지 않는다. 계좌는 표시용 별칭으로 식별한다. 원본과 감사 기록은 환경별 로컬 JSONL에 남긴다. 한투 시세를 모바일 화면에 재표시할 수 있는 범위는 API 이용 조건을 연결 전 확인한다.

로컬 파일 기록이 끝나면 `monitor_outbox.jsonl`에 게시 작업을 넣는다. 게시 작업은 별도 비동기 큐에서 실행하므로 AWS 지연·실패가 시세 웹소켓과 주문 실행을 막지 않는다. 재시도는 같은 `event_id`를 사용한다. 스냅샷은 `(environment, entity)`별 단조 증가 `version`으로 조건부 갱신해 늦게 도착한 옛 값이 최신 값을 덮지 않게 한다. 이 버전은 재시작 후에도 이어지는 로컬 상태에 저장한다. 이벤트는 고정 ID의 조건부 `PutItem`으로 중복을 막는다. 게시 성공, 이미 게시된 동일 이벤트, 최신 버전에 밀린 옛 스냅샷만 처리 완료로 기록한다. 그 밖의 실패는 체크포인트를 전진시키지 않고 재시작 때 outbox를 다시 재생한다. [DynamoDB 조건부 쓰기·버전 관리](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/BestPractices_OptimisticLocking.html).

## AWS 읽기 모델

dev와 prd는 물리적으로 별도 DynamoDB 테이블과 쓰기 권한을 사용한다. `dev` 파드는 `*_dev`, `prd` 파드는 `*_prd` 테이블만 쓸 수 있다. Lambda는 읽기 권한만 가진다. 개인 사용 규모에서는 다음 네 테이블로 시작한다.

| 테이블 | 키 | 용도 |
| --- | --- | --- |
| `monitor_state_dev` / `monitor_state_prd` | `PK=ENTITY#<kind>#<id>`, `SK=LATEST` | 종목·계좌·대시보드·heartbeat의 최신 작은 스냅샷 |
| `monitor_events_dev` / `monitor_events_prd` | `PK=DAY#<YYYY-MM-DD>`, `SK=<UTC시각>#<event_id>` | 날짜별 최근 사건·판단·주문·장애 목록 |

모든 항목에 `schema_version`, `environment`, `source_at`, `recorded_at`, `published_at`, `version` 또는 `event_id`, `release_id`를 포함한다. `source_at`은 시장·브로커의 원천 시각, `recorded_at`은 Mac에서 확정한 시각, `published_at`은 AWS 게시 시각이다. 앱에 보이는 데이터가 오래됐는지 판별할 수 있게 Lambda는 응답에 `server_time`도 넣는다. 테이블 항목은 작게 유지하고 원본 봉·기사·공시·계좌 원장을 복제하지 않는다. DynamoDB 항목 크기 상한은 400KB다. [AWS 항목 크기 안내](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/bp-use-s3-too.html).

API의 첫 읽기 계약은 `GET /v1/{env}/dashboard`, `GET /v1/{env}/symbols/{code}`, `GET /v1/{env}/events?date=&limit=&cursor=`다. Lambda는 허용된 종목·날짜·최대 결과 수를 검사하고 `GetItem`/`BatchGetItem`/키 기반 `Query`를 사용한다. 이벤트 목록은 하루 파티션을 최신순으로 조회하고 이전 날짜는 커서로 이어간다. 전체 테이블 `Scan`은 앱 요청 경로에 넣지 않는다. 대시보드 최신값은 기본적으로 테이블의 강한 일관성 읽기로 가져오고, 이벤트 목록은 기본 일관성 읽기와 중복 제거를 적용한다. GSI는 초기 읽기 경로에 두지 않는다. [DynamoDB 일관성](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/HowItWorks.ReadConsistency.html), [Query 권장](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/bp-query-scan.html).

안드로이드 앱은 앞 화면에서 15~30초 간격으로 조회하고 백그라운드에서는 지속 폴링하지 않는다. `source_at`과 heartbeat가 오래되면 마지막 값을 그대로 **지연/오프라인**으로 표시한다. 초기 기준은 스냅샷 2분 초과, heartbeat 90초 초과이며 실제 수집 지연을 관찰해 조정한다. 앱에서 캐시한 오래된 예측이나 판단을 현재 신호처럼 표시하지 않는다. API 실패 시 마지막 성공 응답과 그 시각을 표시한다.

## 인증과 권한

API Gateway에는 JWT 인증을 적용하고 개인 사용자 계정에는 Cognito 등의 발급자를 연결한다. Lambda는 인증된 사용자만 읽으며, `{env}` 경로를 그대로 신뢰하지 않고 사용자 권한에서 dev/prd 접근 가능 범위를 확인한다. 안드로이드 앱에는 AWS 자격 증명과 KIS 키를 넣지 않는다. Lambda 역할은 모니터링 테이블의 `GetItem`·`BatchGetItem`·`Query` 권한만 가진다. Mac 업로더의 dev/prd 쓰기 자격 증명도 서로 다른 테이블로 제한한다. [API Gateway JWT 인증](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-jwt-authorizer.html), [환경별 DynamoDB IAM 분리](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/iam-policy-separate-environments.html).

모니터링 API에는 주문 생성·취소 엔드포인트를 두지 않는다. 앱의 화면에는 환경 이름을 항상 표시하고, 실전 화면에는 실전 계좌 값이라는 점과 마지막 동기화 시각을 분명히 표시한다.

## 검증 조건

- dev와 prd의 계좌·종목·주문 이벤트가 다른 테이블에서만 조회되는지, dev 사용자 토큰으로 prd 조회가 거부되는지 확인한다.
- Mac의 AWS 연결을 끊어도 시세·판정·주문 경로가 계속되고, 앱은 지연 상태를 표시하며 복구 후 outbox가 중복 없이 재생되는지 확인한다.
- 늦게 도착한 스냅샷과 반복 전송된 이벤트가 최신 화면을 되돌리거나 목록을 중복시키지 않는지 확인한다.
- 실전 주문 접수·부분 체결·취소가 KIS 조회와 모바일 표시에서 같은 상태로 정리되는지 확인한다.
- API 인증 실패, 임의 종목·날짜·큰 `limit`, 과도한 조회를 제한하고 비용·오류율·최신성 지표를 관찰한다.
