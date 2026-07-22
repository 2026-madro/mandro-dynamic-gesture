# mandro-dynamic-gesture

프로젝트 전체 개요, 아키텍처, 진화 과정, 트러블슈팅 히스토리는
**[`HANDOVER.md`](HANDOVER.md)** 참고.

세부 설계/프로토콜 문서는 [`app/src/main/java/com/mandro/docs/`](app/src/main/java/com/mandro/docs/)에 있음
(`HANDOVER.md`의 "문서 지도" 섹션에 각 문서 설명 정리돼 있음).

## `docs/archive/` 폴더

`docs/` 바로 아래에 있는 문서는 전부 지금도 유효한 것들이고, **더 이상 최신이
아니게 된 문서는 삭제하지 않고 [`docs/archive/`](app/src/main/java/com/mandro/docs/archive/)로
옮겨서 보존**함 — 왜 그렇게 결정했는지, 뭐가 바뀌었는지 같은 히스토리가 나중에
필요할 수 있어서. 예: 서버 기반 구조였던 시절의 README, 로컬 전환 **사전**
설계안, 기능 구현 **전** 설계 논의 문서 등. 각 archive 파일 맨 위에 왜
구버전이 됐는지와 최신 내용이 어디로 옮겨갔는지 안내가 붙어있음 — 자세한
목록은 `HANDOVER.md` §10("문서 지도") 참고.
