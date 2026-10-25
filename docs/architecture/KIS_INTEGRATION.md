# 한국투자증권 Open API 연동 명세

한국투자증권(KIS)을 이 앱의 증권사로 확정한다. KIS Open API 샘플을 연동 기준으로 사용하며, 실제 계좌 권한과 응답 필드는 첫 연결 시험에서 확인한다. 웹소켓 수신과 Jev 분석은 독립적인 비동기 작업으로 실행한다. 모의계좌·실전계좌 주문과 환경 격리는 [dev·prd 거래 환경](ENVIRONMENTS.md)을 따른다.

## 채널과 API

| 앱 기능 | KIS 채널·함수 | 앱에서의 사용 |
| --- | --- | --- |
| KRX 실시간 체결 | 웹소켓 `H0STCNT0` | KRX 시장별 1분 봉 |
| NXT 실시간 체결 | 웹소켓 `H0NXCNT0` | NXT 시장별 1분 봉 |
| 통합 실시간 체결 | 웹소켓 `H0UNCNT0` | 통합 1분 봉의 기준 후보 |
| 실시간 호가 | KRX `H0STASP0`, NXT `H0NXASP0`, 통합 `H0UNASP0` | 시장별 체결 가능 가격·수량과 시세 최신성 확인 |
| 당일·과거 분봉 | `inquire_time_itemchartprice`, `inquire_time_dailychartprice`; 시장 코드 `J`(KRX), `NX`(NXT), `UN`(통합) | 시작 시 모델 준비, 웹소켓 누락 구간 복구 |
| 계좌·주문 조회 | `inquire_balance`, `inquire_daily_ccld`, `inquire_psbl_rvsecncl` | 보유량·현금·주문/체결 내역과 정정·취소 가능한 미체결 수량 재확인 |
| 주문·정정·취소 | `order_cash`, `order_rvsecncl`; 시장 경로 `KRX`, `NXT`, `SOR` | 실제 주문 기능을 추가할 때 주문 실행기에 연결 |

출처: [KIS 웹소켓 채널 예제](https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions_ws.py), [당일 분봉 조회](https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_time_itemchartprice/inquire_time_itemchartprice.py), [과거 날짜 분봉 조회](https://github.com/koreainvestment/open-trading-api/tree/main/examples_llm/domestic_stock/inquire_time_dailychartprice), [잔고 조회](https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_balance/inquire_balance.py), [주문·체결 조회](https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_daily_ccld/inquire_daily_ccld.py), [정정·취소 가능 주문 조회](https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/inquire_psbl_rvsecncl/inquire_psbl_rvsecncl.py), [현금 주문](https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/order_cash/order_cash.py), [정정·취소](https://github.com/koreainvestment/open-trading-api/blob/main/examples_llm/domestic_stock/order_rvsecncl/order_rvsecncl.py).

## 앱 어댑터

- `KisAuth`: 시작할 때 고정한 실전·모의 환경에 맞는 REST·웹소켓 주소, 계좌, 토큰과 웹소켓 인증을 관리한다. 키와 토큰 저장소는 환경별로 분리하며 데이터 파일과 로그에 넣지 않는다.
- `KisMarketData`: KRX·NXT·통합 체결과 시장별 호가를 구독하고, KIS 필드를 `stock_code`, `venue`, `market_phase`, `trade_at`, `received_at`, `price`, `volume` 등 앱 공통 형식으로 바꾼다.
- `KisBarRecovery`: `J`, `NX`, `UN` 분봉 조회로 누락 구간을 보정한다. 당일 분봉 API는 한 번에 최대 30건이며 전일 분봉은 별도 날짜 조회 API를 사용한다. 현재 진행 중인 봉과 완성된 봉을 구별하고, 웹소켓 봉과 REST 분봉이 다른 경우 원본·수정 버전과 차이를 기록한다.
- `KisAccount`: 잔고·주문/체결 내역과 정정·취소 가능 주문을 조회해 `account_snapshot`을 만든다. 주문 상태는 웹소켓 통지가 있더라도 REST 조회로 재확인한다.
- `KisOrder`: 판정기와 분리한 주문 실행기다. dev에서는 모의투자, prd에서는 실전투자의 해당 API에만 주문한다. `EXCG_ID_DVSN_CD`에 `KRX`, `NXT`, `SOR` 중 해당 환경에서 허용된 경로를 전달하고 주문 의도·접수 ID·체결·취소를 환경별로 기록한다. 주문 전 환경·계좌·서버 주소·주문 식별자의 일치를 강제한다.

통합 체결 채널을 통합 봉의 기준으로 쓰고 KRX·NXT 개별 채널을 시장별 봉의 기준으로 쓰는 구성을 먼저 시험한다. 한 체결이 통합·개별 채널에 모두 나타날 수 있으므로 두 채널의 거래량을 다시 합산하지 않는다. KIS 메시지의 시장 코드, 시간 정밀도, 체결 순번과 재연결 후 중복 형태를 실제 샘플로 확인한 뒤 정규화 규칙을 확정한다.

## 시작·장중·복구 순서

1. 시작 시 인증을 준비하고 KRX·NXT 거래 대상과 시장 상태를 확인한다. `J`, `NX`, `UN` 과거 분봉으로 모델 상태를 복원한 뒤 필요한 웹소켓을 구독한다.
2. 장중에는 웹소켓 수신 작업이 메시지를 큐에 넣고, 시장별 봉 작업이 매분 봉을 확정한다. 호가는 시장별 최신 시각과 함께 메모리에 둔다.
3. 한 채널이 끊기면 해당 시장과 통합 시세의 완전성을 낮추고 신규 진입을 보류한다. 재접속 후 해당 시장 코드의 REST 분봉을 조회해 빠진 시각을 채우고 모델 입력을 시간순으로 다시 재생한다.
4. 판단 전 `KisAccount`의 잔고·미체결 상태와 시장별 호가 시각을 검사한다. 주문 기능이 켜진 환경에서는 `KisOrder`가 이 정보를 주문 직전에 다시 확인한다. 응답이 불명확하면 주문·체결 조회로 대조한 후에만 재시도한다.

## 첫 연결 시험에서 확인할 항목

- 실전·모의 계좌 각각에서 KRX·NXT·통합 웹소켓 채널과 `J`·`NX`·`UN` 분봉 조회가 실제로 제공되는 범위.
- 통합 체결의 시장 식별 필드, 개별 체결과의 중복 관계, 체결 순서·시각 정밀도.
- KRX 공식 종가를 반환하는 필드와 NXT 종료 기준 가격을 산출할 자료의 품질.
- NXT 거래 대상 종목을 확인하는 제공처와 장중 시장 중단·재개 상태.
- `SOR` 및 시장 지정 주문의 계좌 권한, 주문 유형, 모의 환경 지원 여부. 초기 판단·기록 단계에서는 주문을 보내지 않고, dev 모의주문 검증 후 prd 실전주문을 연다.
