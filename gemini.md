# 솔라 라이브 데모 업그레이드 정리 (`gemini.md`)

> 작업일: 2026-11-23  
> 대상 프로젝트: `솔라 라이브 — AI 라이브 캐릭터 미니 데모`

기존 프로젝트의 핵심 엔진(공정 규칙 비교·음성 발화 시간 제약·팬 기억·안전 필터·시뮬레이션 재현성)은 그대로 유지하면서, **실제 방송처럼 살아 있는 캐릭터·채팅·음성·진행 체험**을 끌어올리기 위해 진행한 개선 내역 정리입니다.

---

## 1. 캐릭터 말투 & 세계관 정비

### 문제점
- 기존 `~함 해요`, `~됨 해요` 어미가 TTS에서 부자연스럽게 끊기고, 일부 유창한 어른 말투와 톤이 어긋남.
- `"솔라는 배가 없거든요!"` 설정과 `"솔라 침 고임 해요"` 대사 간의 소소한 세계관 모순 존재.
- `"스크롤"`, `"롤러코스터"`, `"밥맛없어"` 등 짧은 키워드 오탐 및 물음표(`?`) 질문에 대한 일괄 회피(`"비밀이에요"`).

### 개선 내용
- **말투 통일**: `~함 해요` 어미를 걷어내고, 지구 문화를 별의 시선으로 엉뚱하게 해석하는 밝은 구어체 + 추임새(`"반짝!"`, `"우와~"`)로 통일.
- **키워드 오탐 차단 & 확장**: `스크롤/컨트롤/롤러/롤빵/트롤` 및 `밥맛/밥그릇` 오탐 제외, 음식 키워드에 `초밥`·`국밥`·`파스타` 추가.
- **자주 묻는 질문 맞춤 응답**: 나이/몇 살, 별에서 먹는 것, 노래, 메뉴 추천, 기분, 고향 질문에 세계관에 맞게 직접 대답하도록 분기 추가.

---

## 2. 봇 채팅 현실화 (화면 데모 전용)

### 문제점
- 27개 고정 문장과 `형용사+동물+번호` 닉네임 패턴이 반복되어 한눈에 봇 티가 남.
- 솔라가 어떤 혼잣말/이야기를 해도 무조건 `"ㅋㅋㅋㅋ"`, `"귀여워"` 같은 범용 리액션만 출력.

### 개선 내용 (`BotAudience` `realistic=true`)
- **시뮬레이션 테스트와 분리**: 기존 생성자(`realistic=false`)는 그대로 두어 고정 시드(42) 수치 검증 테스트를 100% 보존하고, 실시간 웹 데모(`LiveService`)에서만 `realistic=true` 활성화.
- **닉네임 다양화**: 짧은 한글 별명(`새벽감성`, `망고라떼`), 유머 닉네임(`퇴근하고싶다`, `치킨이진리`), 영문+숫자(`star_luna`, `mint99`) 등 4가지 스타일 혼합.
- **성향별·조합형 채팅**: `QUIET`(짧은 채팅), `REGULAR`(단골 말투), `WHALE`(애정 어린 후원 메시지) 성향을 분리하고, 음식/게임 채팅과 질문 답을 조합형 변주로 생성.
- **맥락형 리액션**: 솔라의 혼잣말·에피소드 내용마다 전용 봇 리액션(예: 구름에 걸린 이야기 → `"꼬리별 말려줘야겠다"`, `"구름에 걸렸대ㅋㅋㅋ"`)을 매핑.

---

## 3. 방송 주도감 강화 (밸런스 게임 & 솔라 2부작 이야기)

### 문제점
- 채팅 한 줄에 답하는 비중이 높아, 캐릭터가 방송을 이끌기보다 수동적으로 댓글만 읽는 느낌.

### 개선 내용 (`Director` `showHostMode=true`)
- **실시간 밸런스 게임 코너 (6종)**
  - `부먹 vs 찍먹`, `민초 vs 반민초`, `여름 에어컨 vs 겨울 이불`, `평생 라면 vs 평생 치킨`, `아침형 vs 올빼미형`, `푸른 바다 vs 시원한 산`.
  - **흐름**: 선언(`BALANCE_START`) → 봇들이 A/B 두 진영으로 갈려 토론 + 실시간 투표 게이지 갱신(`/topic/topic`) → 14초경 중간 판세 중계(`BALANCE_MID`) → 30초 마감 및 솔라의 별빛 판결(`BALANCE_END`, 참여자 전원 인정).
- **솔라 주도 2부작 미니 에피소드 (6종)**
  - `편의점 자동문 사건`, `월요일 없는 별요일`, `도도한 지구 고양이`, `고향 별이 보인 밤`, `지구 노래 연습 중`, `지구 음식 백 가지 도감`.
  - **흐름**: `HOST_EPISODE_EVERY = 3` 차례마다 솔라가 먼저 본인 이야기 도입(`EPISODE_START`) → 시청자 맞춤 호응 & 1회 반응 → 솔라가 뒷이야기 마무리(`EPISODE_END`).
- **즉시 체험 트리거**: 화면에서 **`⚖️ 밸런스 게임 열기`**, **`📖 솔라 이야기 듣기`** 버튼으로 다음 차례에 바로 코너를 열 수 있음.

---

## 4. 애니메 VTuber UI & 무료 한국어 뉴럴 음성 (`edge-tts`)

### 비주얼 & 립싱크
- **오리지널 애니메 ‘솔라’ 투명 PNG 스프라이트 6종** 제작 및 적용 (`src/main/resources/static/assets/sola_*.png`):
  - `sola_happy_closed.png` (기본 대기)
  - `sola_happy_open.png` (일반 발화)
  - `sola_blink.png` (자동 눈 깜빡임 / `CALM` 위로 표정)
  - `sola_excited.png` (별 눈 반짝 / `EXCITED`)
  - `sola_touched.png` (감동 눈물 반짝 / `TOUCHED`)
  - `sola_thinking.png` (호기심 곁눈질 / `THINKING`)
- **Live2D풍 모션**: 상시 호흡 + 발화 시 부드러운 고개 흔들림 + 주기적 눈 깜빡임 + 감정별 배경 오라 + **Web Audio API (`AnalyserNode`) 기반 실시간 음성 볼륨 립싱크 & 이퀄라이저**.

### 무료 뉴럴 TTS (`TtsService` / `TtsController`)
- API 키 없이 무료로 쓰는 **`edge-tts`** 를 백엔드 `/api/tts`로 연동 (LRU 캐시 300개, 9초 타임아웃, 일시 오류 시 1회 자동 재시도).
  - **솔라**: `ko-KR-SunHiNeural` (금속성 피치 변조가 생기지 않는 `-1Hz ~ +5Hz` 자연 범위 + 감정별 속도 조절)
  - **후원 알림**: `ko-KR-HyunsuMultilingualNeural` (자연스러운 남성 아나운서 톤으로 대비)
- 대본 사전 녹음이 아니라 **대사 생성 직후 브로드캐스트 전 1초 사전 합성·캐시(`TtsService.prepare`)** 방식이라 실시간 LLM 즉흥 대사에서도 그대로 동작.
- `edge-tts` 미설치 환경 시 브라우저 내장 `speechSynthesis`로 자동 폴백 (`ttsStatusReady` 대기로 초기화 레이스 차단).

### 화면 UX 개선
- 상단 바에 **규칙 전환(`A / B / C`)** 및 **봇 수 전환(`1:1 (0명)` / `100` / `300` / `1000`)** 바로 배치.
- 질문·밸런스 게임 진행 시 채팅창 상단 **고정 참여 카드 + 원클릭 빠른 답변/투표 버튼** 및 화면 우상단 **실시간 A vs B 투표 전광판** 표시.
- 채팅창 **닉네임 8색 팔레트**, 유형 배지(`💎` 큰손, `★` 단골, `나`), 솔라가 읽은 채팅(`⭐ 솔라 답함`)·하트 준 채팅(`❤ 솔라 봄`) 하이라이트, 내 채팅 반응 시 토스트 알림, `↓ 새 채팅 보기` 점프 버튼 추가.
- 되읽기 대사일 때 자막 상단 라벨(`who`)에 채팅 원문이 중복 노출되던 버그 수정.

---

## 5. 주요 변경·추가 파일

| 파일 | 역할 |
|---|---|
| `src/main/resources/characters/sola.md` | 솔라 말투·세계관 설정표 리뉴얼 |
| `src/main/java/com/livecharacter/engine/LineWriter.java` | 구어체 대사 정리, 키워드 오탐 방지, 질문 패턴 맞춤 응답 |
| `src/main/java/com/livecharacter/engine/Director.java` | 밸런스 게임 6종, 2부작 미니 에피소드 6종, `showHostMode`, 빠른 답장/진행 상태 |
| `src/main/java/com/livecharacter/engine/DonationQueue.java` | 감사·후원 답장 말투 자연화 |
| `src/main/java/com/livecharacter/engine/LiveRoom.java` | 복귀 팬 인사 말투 자연화 |
| `src/main/java/com/livecharacter/sim/BotAudience.java` | 화면 데모용 `realistic` 닉네임·성향별 조합형 채팅·자연스러운 후원 메시지 |
| `src/main/java/com/livecharacter/live/LiveService.java` | 밸런스 게임/에피소드 진행, 실시간 투표 브로드캐스트(`/topic/topic`), 코너 트리거, `ChatView` 확장 |
| `src/main/java/com/livecharacter/live/LiveController.java` | `ControlIn.trigger` 코너 수동 실행 지원 |
| `src/main/java/com/livecharacter/live/TtsService.java` | `edge-tts` 뉴럴 음성 합성 + LRU 캐시 |
| `src/main/java/com/livecharacter/live/TtsController.java` | `GET /api/tts`, `GET /api/tts/status` REST API |
| `src/main/resources/static/assets/sola_*.png` | 애니메 VTuber 솔라 투명 PNG 스프라이트 6종 |
| `src/main/resources/static/index.html` | VTuber 리깅/립싱크, 뉴럴 TTS 재생, 밸런스 게임 HUD/카드, 채팅 하이라이트, 상단 빠른 설정 |
| `src/test/java/com/livecharacter/engine/BotAndToneTest.java` | 키워드 오탐 방지, 현실화 봇, 밸런스 게임·에피소드 흐름 테스트 |
| `src/test/java/com/livecharacter/engine/DirectorTest.java` | 리뉴얼된 이어 말하기 템플릿 문구 반영 |

---

## 6. 실행 및 테스트 방법

```bash
# 1) 무료 뉴럴 음성용 패키지 (최초 1회, 미설치 시 브라우저 TTS로 자동 폴백)
pip install edge-tts

# 2) 전체 테스트 실행 (43개 테스트)
.\gradlew.bat test

# 3) 데모 서버 실행 → http://localhost:8091
.\gradlew.bat bootRun
```

### 검증 현황
- JUnit 5 테스트 **43개 전체 통과** (기존 40개 시뮬레이션·엔진·검사 테스트 + 신규 3개)
- `GET /api/tts/status` → `Edge Neural TTS (ko-KR-SunHiNeural)` 정상 활성 확인
- `GET /api/tts`, `GET /assets/sola_*.png` → HTTP 200 정상 응답 확인

---

## 7. 실테스트 피드백 반영 (2차 폴리싱)

실제 방송 테스트 중 발견한 6가지 체감 문제를 추가로 개선했습니다.

1. **음성 중간 끊김 & 진행 바 초과 해결 (`TtsService.prepare` + 실측 길이 동기화)**
   - 서버가 `/topic/speak`·`/topic/donation-speak`를 보내기 전에 전용 스레드풀(`ttsPool`)에서 `edge-tts` MP3를 **미리 합성·캐시**하고, 실제 MP3 바이트 길이(48kbps CBR = 초당 6,000바이트)로 정확한 `durationMs`를 계산해 `speakMs`/`readMs`로 내려줌.
   - 후원은 `readText`(아나운서)와 `thanks`(솔라)를 병렬 사전 합성하고, 1단계(후원 알림 읽는 동안 솔라 입 닫고 경청) → 2단계(솔라 감사 자막·표정·립싱크)로 정확히 분리.
2. **중간중간 브라우저 로봇 TTS로 튀는 현상 방지**
   - 백엔드 `edge-tts` 일시 오류 시 1회 자동 재시도 + 9초 타임아웃, 프론트에서도 뉴럴 음성 모드일 땐 1회 재시도하고 이질적인 Windows 기본 TTS로 갑작스럽게 전환되지 않도록 보호.
3. **방송 준비중(`PRE_LIVE`) 채팅 흐름 & 전체 채팅 속도 현실화**
   - `BotAudience.Phase`(`PRE_LIVE` → `OPENING` → `LIVE` → `CLOSING`) 도입: 준비중엔 조용히 `"ㅎㅇㅇ"`, `"오 방송 켰네~"` 대기 채팅만 천천히 올라오다, 방송 시작 시 `"하이하이~"` 인사로 전환.
   - 실제 방송의 눈팅 시청자 비율을 반영해 화면 데모(`realistic=true`) 채팅 간격을 `3.2배` 완화 (300명 기준 초당 3~4줄로 읽기 편하게).
4. **에피소드 티키타카 & 채팅 떡밥 연계 밸런스 게임**
   - **에피소드 티키타카**: 도입 후 아무 채팅이나 읽던 문제를 고쳐, 방금 에피소드 키워드에 반응한 시청자 채팅만 2회 콕 집어 맞춤 티키타카 대사(`FollowUp`)로 받아친 뒤 뒷이야기로 마무리.
   - **채팅 떡밥 연계 밸런스 게임**: 최근 채팅 속 `탕수육`·`민초`·`에어컨/이불`·`바다` 등을 감지해 *"어? 방금 ○○님이 음식 얘기 꺼내서 생각났어요! 부먹 vs 찍먹 투표 열어 볼까요!"*로 자연스럽게 열고, 투표 중에도 솔라 본인 취향·유도 멘트(`banterA`/`banterB`)로 소통.
5. **표정·립싱크 자연화 (9종 스프라이트 + 히스테리시스)**
   - `sola_excited_closed.png`, `sola_touched_closed.png`, `sola_thinking_open.png` 3종을 추가해 **모든 감정에서 눈/눈썹은 고정된 채 입만 자연스럽게 여닫히도록** 개선 (음절마다 별 눈·눈물이 깜빡이던 현상 제거).
   - 오디오 볼륨 EMA 스무딩 + 최소 유지시간(85ms) + 55ms 페이드로 입 떨림 제거, 발화 종료 후 대기 상태에선 부드럽게 기본 미소로 복귀.
6. **투표 중복 클릭 방지 & 로봇 음색 제거 & 엉뚱한 답·배지 누락 수정**
   - **투표 카드 1회 클릭 후 자동 닫힘**: 질문·밸런스 게임 버튼 클릭 시 즉시 참여 완료 토스트를 띄우고 카드를 닫아 중복 전송 방지 (밸런스 게임 실시간 게이지는 화면 우상단 전광판에서 유지).
   - **금속성 로봇 음색 제거**: `ko-KR-SunHiNeural` 과도한 피치 시프트(`+10~15Hz`)를 `-1Hz ~ +5Hz` 자연 범위로 조정하고, 후원 알림을 `ko-KR-HyunsuMultilingualNeural`로 교체, `/api/tts/status` 초기화 레이스 차단.
   - **일상 채팅 엉뚱한 답변 방지 (`LineWriter.specificOther`)**: 웃는 리액션은 실제 `ㅋㅋ/ㅎㅎ` 채팅에만 쓰도록 분리하고, `바다/휴가`, `탕수육`, `민트초코`, `날씨/에어컨/이불`, `월요일/출퇴근`, `반려동물`, `커피`, `올빼미`, `축하`, `칭찬`에 맥락별 맞춤 응답 추가.
   - **`⭐ 솔라 답함` 배지 누락 수정**: 1:1 반응(`fan`)뿐 아니라 `answers`·`talk`·`balance_start`·`donation` 언급 대상도 하이라이트하고, 500ms 채팅 묶음 전송(`flushChat`)보다 발화가 먼저 도착한 경우 `pendingMarks` 큐(3.5초 보류)로 도착 즉시 배지를 부착.

---

## 8. 전체 동작 파이프라인 요약

```text
[봇 시청자(BotAudience) + 유저 채팅]
            │
            ▼
[순수 Java 엔진: LiveRoom · Director · DonationQueue · LineWriter]
  - 코너/차례 결정 (오프닝 · 에피소드 티키타카 · 밸런스 게임 · 질문 · 후원 · 일반 반응)
  - 공정 규칙(PickRule)으로 목소리 1명 + 화면 ❤ 3명 선정, 팬 기억(FanMemory) 조회
  - 대사 + 감정(Emotion 5종) 생성
            │
            ▼
[백엔드 음성 사전 합성: TtsService (edge-tts + LRU 캐시)]
  - 브로드캐스트 직전 ttsPool에서 mp3 실시간 합성·캐시 + 실제 재생 길이(ms) 계산
            │
            ▼  (STOMP: /topic/chat, /topic/speak, /topic/topic, /topic/donation-speak)
[브라우저 UI: index.html]
  - 캐시된 /api/tts mp3 즉시 재생 + Web Audio(AnalyserNode) 실시간 9종 스프라이트 립싱크
  - 서버 실측 길이(speakMs / readMs)에 맞춰 자막·진행 바·표정 동기화
```
