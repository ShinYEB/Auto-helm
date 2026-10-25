# dev·prd 거래 환경과 승격 계약

목표는 동일한 애플리케이션 코드로 한국투자증권 모의투자 계좌에 실제 API 주문을 보내는 `dev`와 실전 계좌에 실제 주문을 보내는 `prd`를 운영하는 것이다. `dev`의 모의투자 주문은 앱 내부의 가상 체결 시뮬레이션과 구별한다. 매매 로직과 KRX·NXT 시장 처리는 공유하지만, 실행 인스턴스·계좌·인증정보·기록·DB·주문 권한은 환경별로 분리한다. 처음 구현할 때는 판단·기록부터 만들고, 이후 `dev` 모의주문과 `prd` 실전주문을 차례로 연결한다.

## 배치와 경계

```text
같은 검증된 코드 이미지 + 고정 모델 버전
             ├─ dev namespace / dev 파드 ─ dev 비밀정보 ─ KIS 모의 REST·WS ─ 모의계좌
             │                         └─ dev 파일 볼륨 + dev DynamoDB Local
             │                         └─ AWS dev 모니터링 테이블
             └─ prd namespace / prd 파드 ─ prd 비밀정보 ─ KIS 실전 REST·WS ─ 실전계좌
                                       └─ prd 파일 볼륨 + prd DynamoDB 서비스
                                       └─ AWS prd 모니터링 테이블
```

Mac에서 파드를 실행할 경우 별도 네임스페이스, 서비스 계정, 네트워크 정책, 비밀정보, 영구 볼륨을 둔다. 네임스페이스만으로 Mac 하드웨어 장애가 분리되지는 않으므로 prd의 원본 파일·모델·설정은 별도 위치에 백업한다. 각 환경의 수집기, 추론기, Jev 작업, 판정기, 주문 실행기는 해당 환경의 파드에서만 실행한다. 같은 코드 이미지라도 런타임 환경은 파드 생성 시 고정하고, 실행 중 `dev ↔ prd` 전환 명령은 제공하지 않는다.

| 경계 | dev | prd |
| --- | --- | --- |
| KIS 계좌 | 모의투자 계좌 | 실전투자 계좌 |
| KIS 인증 | 모의 App Key·Secret, 모의 계좌번호·토큰·웹소켓 승인키 | 실전 App Key·Secret, 실전 계좌번호·토큰·웹소켓 승인키 |
| KIS 접속 | `vps` REST, `vops` 웹소켓 | `prod` REST, `ops` 웹소켓 |
| 주문 | 한투 모의투자 API 주문·정정·취소 | 한투 실전투자 API 주문·정정·취소 |
| 현금주문 TR ID 예시 | 매수 `VTTC0012U`, 매도 `VTTC0011U` | 매수 `TTTC0012U`, 매도 `TTTC0011U` |
| 파일 원본 | `data/dev/` 전용 볼륨 | `data/prd/` 전용 볼륨 |
| 이벤트 인덱스 | 별도 DynamoDB Local 인스턴스·파일 | 별도 AWS DynamoDB 테이블과 최소 권한 자격 증명 |
| 안드로이드 모니터링 | 별도 AWS dev 테이블에 스냅샷·이벤트 게시 | 별도 AWS prd 테이블에 스냅샷·이벤트 게시 |
| 화면·알림 | `DEV · 모의투자` 표시 | `PRD · 실전투자` 표시 |

KIS 공식 설정 예시는 모의·실전용 키·계좌번호와 REST·웹소켓 주소를 따로 정의한다. 주소·인증 정보는 예시 파일을 코드에 복사해 고정하지 않고 배포 설정에서 주입하며, 시작 시 `APP_ENV`, 계좌 종류, REST·웹소켓 호스트, 비밀정보 출처, 파일 경로, DB 목적지가 같은 환경인지 검증한다. 하나라도 맞지 않으면 주문 실행기와 앱 시작을 실패 처리한다. 환경별 토큰 캐시·웹소켓 승인키·주문 ID 범위도 공유하지 않는다. [KIS 설정 예시](https://github.com/koreainvestment/open-trading-api/blob/main/kis_devlp.yaml), [KIS 인증 예시](https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/kis_auth.py).

현금주문 TR ID도 환경별로 달라진다. 위 값은 KIS의 `order_cash` 예시에 있는 값이며, 실제 연결 시 계좌·주문 유형별 지원 범위를 다시 확인한다. [KIS 현금주문 예시](https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/order_cash/order_cash.py).

`prd`에서는 관리형 DynamoDB를 검색 인덱스로 쓴다. AWS는 DynamoDB Local을 개발·시험용으로 명시하므로 `prd`에 사용하지 않는다. dev도 [안드로이드 원격 모니터링](MOBILE_MONITORING.md)을 위해 AWS dev 테이블에 게시하므로 AWS 계정·연결·사용량 비용이 필요하다. 장중 판단의 원본과 복구 기준은 환경별 JSONL이며, DB는 검색·모니터링용 파생 데이터다. AWS DB 장애 시 인덱싱·게시를 보류하고 원본 파일에서 재생한다. `prd`가 AWS에 연결되지 못해도 주문·계좌 상태 복구에 DB 인덱스를 의존하지 않는다. [AWS DynamoDB Local 사용 범위](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/DynamoDBLocal.UsageNotes.html).

## 주문의 단일 실행 경로

`decision`은 주문 제안이다. `OrderExecutor`만 KIS 주문 API를 호출하며, dev와 prd 각각 독립 실행한다. 주문 요청에는 `environment`, `account_alias`, `decision_id`, `client_order_id`, `release_id`, `code_version`, `model_version`, `venue`, `quantity`, `limit_price`, `created_at`을 기록한다. 주문 전 환경별 계좌번호·잔고·미체결·호가 시각·시장 상태·종목·수량·가격·일일 손실 한도를 재확인한다. `APP_ENV=prd`인 실행기가 모의 계좌를 쓰거나, `APP_ENV=dev`인 실행기가 실전 키·실전 계좌를 읽으면 즉시 중단한다.

`client_order_id`는 앱 내부의 중복 방지·대조용 ID다. KIS 현금주문 API가 이 ID를 서버 측 멱등성 키로 받아준다는 가정은 하지 않는다. 제출 전 의도를 파일에 영구 기록하고, 제출 결과가 불확실하면 동일 주문의 재전송을 보류한 채 KIS 주문·체결 내역과 수동 대조가 끝날 때까지 신규 주문을 중단한다.

주문 상태는 `INTENT_RECORDED → SUBMITTING → ACKNOWLEDGED/REJECTED → PARTIAL/FILLED/CANCELED`로 기록한다. 네트워크 시간 초과 후에는 무조건 재주문하지 않고 KIS 주문·체결·미체결 조회로 먼저 대조한다. 재시작 시에도 환경별 주문 의도와 브로커 내역을 대조해 중복 주문을 막는다. 장애·시세 누락·상태 불일치에서는 신규 진입을 중단하고 취소·청산 여부는 확인 가능한 현재 주문 상태로 판단한다. `prd`에는 독립적인 주문 중지 스위치와 보수적인 수량·금액 한도를 둔다.

`dev`의 KRX·NXT·SOR 모의주문 가능 범위는 실전과 같다고 가정하지 않는다. 한투 모의 환경에서 각 시장, 주문 유형, 정정·취소, 체결 통지의 지원 여부를 실제 연결 시험으로 확인하고, 미지원 경로는 테스트 결과에 명시한다. 모의 체결은 실전 유동성·호가·지연·부분 체결을 재현한다고 가정하지 않는다.

## dev에서 prd로 승격

1. 코드 이미지의 변경 불가능한 digest, 설정 스키마, 모델 파일 checksum, Jev 프롬프트·판정 규칙 버전을 한 묶음의 `release_id`로 만든다. 데이터·계좌·비밀정보는 승격하지 않는다.
2. 단위·계약 검사와 과거 기간 백테스트를 통과한다. 시세 누락, 장 단계, KRX·NXT 경로, 비용, 계좌 한도, 주문 중복·재시작 복구를 확인한다.
3. 같은 `release_id`를 dev에 배포해 모의투자 계좌에서 주문·부분 체결·거부·정정·취소·장애 복구를 기록한다. 시험 기간, 최소 표본, 허용 오류율, 손실 한도는 실제 성과를 보고 배포 정책에 수치로 고정한다. 해당 결과와 지원되지 않은 API 항목을 승격 기록에 첨부한다.
4. 승격 작업은 **dev에서 시험한 코드 이미지와 모델 파일을 그대로** prd에 배포한다. prd 전용 계좌·비밀정보·DB·파일 경로만 주입한다. prd에서 별도로 코드를 빌드하거나 모델을 다시 학습해 바꾸지 않는다.
5. prd 배포 직후에는 주문을 멈춘 상태에서 인증·시세·잔고·미체결·시장 상태를 읽기 전용으로 확인한다. 이후 주문 중지 스위치를 해제하고 작은 한도로 시작한다. 첫 실전 주문의 접수·체결·잔고를 KIS 조회와 대조하며, 불일치하면 신규 주문을 중지한다.

승격 기록에는 `release_id`, dev 시험 시각·결과, 승인된 모델·규칙·설정 버전, prd 배포 시각과 롤백 대상 버전을 남긴다. 롤백은 애플리케이션 버전을 되돌리는 일이며 이미 KIS에 접수된 주문을 취소하거나 포지션을 되돌리지 않는다. 롤백 전후 모두 실전 계좌의 주문·체결·잔고를 다시 조회한다.

## 구현 전 확인

- Mac의 파드 런타임과 영구 볼륨·비밀정보 저장 방식. 같은 물리 Mac을 쓰므로 전원·네트워크·디스크 장애는 공통이다.
- 한투 모의투자 계좌의 KRX·NXT·SOR 및 실시간 채널·주문 유형 지원 범위와 실전 계좌 권한.
- `prd` AWS DynamoDB 접근·비용·백업 설정. 별도 테이블과 최소 권한 정책을 사용하고 dev 자격 증명은 prd 테이블 접근을 허용하지 않는다.
- 실제 주문 중지 스위치, 한도, 재시도 정책과 장중 장애 시 복구 절차의 수치 기준.
