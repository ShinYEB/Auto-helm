# 문서 목록

이 저장소의 문서는 목적에 따라 폴더를 나눕니다. 현재 문서는 구현 전 초안이며, API 지원 범위와 운영 기준은 연결 시험과 모의투자 결과를 바탕으로 갱신합니다.

## 제품 요구사항

- [PRD](prd/PRD.md): 목표, 범위, 사용자 시나리오, 기능별 완료 기준과 실전 승격 조건

## 아키텍처와 연동 설계

- [전체 아키텍처](architecture/ARCHITECTURE.md): 구성 요소와 매매 알고리즘
- [데이터 파이프라인](architecture/DATA_PIPELINE.md): 수집·분석·결합·판정의 처리 순서
- [데이터 계약](architecture/DATA_CONTRACTS.md): 원본, 인덱스, 장중 읽기와 복구 기준
- [KRX·NXT 시장 설계](architecture/MARKETS.md): 시장·장 단계별 시세, 예측과 주문 경계
- [한국투자증권 연동](architecture/KIS_INTEGRATION.md): 웹소켓·REST·계좌·주문 어댑터
- [dev/prd 환경](architecture/ENVIRONMENTS.md): 모의투자·실전투자 분리와 승격 절차
- [안드로이드 모니터링](architecture/MOBILE_MONITORING.md): AWS 게시 및 읽기 전용 앱 경로

## 개발 기록 초안

- [프로젝트 개요](blog/01-overview.md)
- [전체 설계 이야기](blog/02-overall-design.md)

처음 읽는다면 **PRD → 전체 아키텍처 → 데이터 파이프라인** 순서를 권장합니다.
