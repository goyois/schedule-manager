// 상단 동기부여 명언 배너: #quote-banner 가 있는 페이지(로그인/대시보드)에서 공통으로 사용한다.
// 현재 시각의 "시간 단위(hour bucket)"로 명언을 고르기 때문에 페이지를 새로고침해도 같은 시간대에는
// 같은 명언이 뜨고, 정시가 지나면 자동으로 다음 명언으로 바뀐다.
const QUOTES = [
  { text: "우리가 반복적으로 하는 행동이 우리 자신이다. 탁월함은 행동이 아니라 습관이다.", author: "아리스토텔레스" },
  { text: "할 수 있다고 믿든 할 수 없다고 믿든, 당신의 믿음이 옳다.", author: "헨리 포드" },
  { text: "나는 실패한 적이 없다. 단지 작동하지 않는 만 가지 방법을 발견했을 뿐이다.", author: "토머스 에디슨" },
  { text: "천 리 길도 한 걸음부터.", author: "노자" },
  { text: "오늘 할 수 있는 일에 전력을 다하라. 그러면 내일은 한 걸음 더 나아가 있을 것이다.", author: "새뮤얼 존슨" },
  { text: "성공은 열정을 잃지 않고 실패를 거듭할 수 있는 능력이다.", author: "윈스턴 처칠" },
  { text: "시작이 반이다.", author: "아리스토텔레스" },
  { text: "가장 어두운 밤도 끝나고 결국 해는 떠오른다.", author: "빅토르 위고" },
  { text: "행동은 모든 성공의 기본 열쇠다.", author: "파블로 피카소" },
  { text: "미래를 예측하는 가장 좋은 방법은 그것을 창조하는 것이다.", author: "피터 드러커" },
  { text: "위대한 일은 열정 없이 이루어진 적이 없다.", author: "랄프 왈도 에머슨" },
  { text: "어제로부터 배우고, 오늘을 위해 살고, 내일을 위해 희망하라.", author: "알버트 아인슈타인" },
  { text: "산을 옮기는 사람은 작은 돌부터 옮기는 사람이다.", author: "공자" },
  { text: "포기하지 않는 한, 실패한 것이 아니다.", author: "알버트 아인슈타인" },
  { text: "습관이 바뀌면 인생이 바뀐다.", author: "윌리엄 제임스" },
  { text: "지금 이 순간이 가장 젊은 때다.", author: "칩 데이비스" },
];

function currentQuote() {
  const hourBucket = Math.floor(Date.now() / (60 * 60 * 1000));
  return QUOTES[hourBucket % QUOTES.length];
}

function renderQuoteBanner() {
  const banner = document.getElementById("quote-banner");
  if (!banner) return;

  const { text, author } = currentQuote();
  banner.innerHTML = `<span class="quote-text">"${text}"</span><span class="quote-author">— ${author}</span>`;
}

renderQuoteBanner();
// 정시가 지났는지 1분마다 확인해서, 바뀌었을 때만 새로 그린다
setInterval(renderQuoteBanner, 60 * 1000);
