'use strict';
(() => {
  const key='zerostreams-web-v1';
  try{
    const parsed=JSON.parse(localStorage.getItem(key)||'null');
    const next=parsed&&typeof parsed==='object'?parsed:{};
    next.settings={...(next.settings||{}),uiLayout:'classic'};
    localStorage.setItem(key,JSON.stringify(next));
  }catch{}
})();
