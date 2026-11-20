function sha256(msg){
 const b=new TextEncoder().encode(String(msg)),K=[],H=[];let n=2,c=0;
 const fr=x=>((x-Math.floor(x))*4294967296)|0;
 while(c<64){let p=1;for(let d=2;d*d<=n;d++)if(n%d===0){p=0;break}if(p){if(c<8)H[c]=fr(Math.sqrt(n));K[c++]=fr(Math.cbrt(n));}n++;}
 const l=b.length,w=new Uint8Array(((l+9+63)>>6)<<6);w.set(b);w[l]=0x80;
 const dv=new DataView(w.buffer);dv.setUint32(w.length-4,(l*8)>>>0);dv.setUint32(w.length-8,Math.floor(l/536870912));
 const W=new Int32Array(64),r=(x,k)=>(x>>>k)|(x<<(32-k));
 for(let o=0;o<w.length;o+=64){
  for(let i=0;i<16;i++)W[i]=dv.getInt32(o+i*4);
  for(let i=16;i<64;i++){const x=W[i-15],y=W[i-2];W[i]=(W[i-16]+(r(x,7)^r(x,18)^(x>>>3))+W[i-7]+(r(y,17)^r(y,19)^(y>>>10)))|0;}
  let [A,B,C,D,E,F,G,Hh]=H;
  for(let i=0;i<64;i++){const t1=(Hh+(r(E,6)^r(E,11)^r(E,25))+((E&F)^(~E&G))+K[i]+W[i])|0,t2=((r(A,2)^r(A,13)^r(A,22))+((A&B)^(A&C)^(B&C)))|0;Hh=G;G=F;F=E;E=(D+t1)|0;D=C;C=B;B=A;A=(t1+t2)|0;}
  H[0]=(H[0]+A)|0;H[1]=(H[1]+B)|0;H[2]=(H[2]+C)|0;H[3]=(H[3]+D)|0;H[4]=(H[4]+E)|0;H[5]=(H[5]+F)|0;H[6]=(H[6]+G)|0;H[7]=(H[7]+Hh)|0;
 }
 const out=new Uint8Array(32),ov=new DataView(out.buffer);H.forEach((h,i)=>ov.setInt32(i*4,h));return out;
}
function famCode(id,secret){const h=sha256(id+"|"+secret+"|cq-family-accept-v1");return String((((h[0]<<24)>>>0)+(h[1]<<16)+(h[2]<<8)+h[3])%1000000).padStart(6,"0");}
(function(){
"use strict";
// Шақыру сілтемесі: /family.html#i=<id>.<secret>&l=kk&n=<аты>
// Бөлігі (#...) серверге ЖІБЕРІЛМЕЙДІ — бұл бет ешқандай деректі сақтамайды және жібермейді.
const T={
kk:{t:"{n} сізді «Отбасылық қалқан» сенімді адамы ретінде қосқысы келеді",d:"CyberQalqan — алаяқтық SMS пен фишинг сілтемелерінен қорғайтын қосымша. Жақыныңызға күмәнді хабарлама келсе, ол бір батырмамен сізге ескерту жібере алады.",
 get:"Сізге келетіні:",g1:"Қысқа ескерту: «жақыныңызға күмәнді хабарлама келді»",g2:"Сілтеме, SMS-код, карта нөмірі ЕШҚАШАН жіберілмейді",g3:"Хабарламаны тек жақыныңыз өзі батырманы басқанда жібереді",
 yes:"✅ Келісемін",no:"✖ Бас тартамын",okT:"Рақмет! Растау коды:",okD:"Осы кодты жақыныңызға жіберіңіз (SMS-пен жауап беріңіз немесе айтып беріңіз). Ол кодты CyberQalqan-ға енгізгенде, байланыс белсенді болады.",
 copy:"📋 Кодты көшіру",copied:"Көшірілді ✔",reply:"📨 SMS-пен жауап беру",replyMsg:"CyberQalqan растау коды: {c}",noT:"Сіз бас тарттыңыз",noD:"Ешқандай байланыс жасалмады. Жақыныңызға бас тартқаныңызды айтыңыз — ол сізді тізімнен өшіреді.",
 bad:"Шақыру сілтемесі қате немесе толық емес. Жақыныңыздан сілтемені қайта жіберуін сұраңыз.",me:"Жақыныңыз",
 rev:"Кейін бас тартқыңыз келсе, жақыныңызға айтыңыз: ол «Отбасылық қалқан» бөлімінен сізді бір батырмамен өшіреді."},
ru:{t:"{n} хочет добавить вас доверенным человеком в «Семейный щит»",d:"CyberQalqan — приложение, защищающее от мошеннических SMS и фишинговых ссылок. Если вашему близкому придёт подозрительное сообщение, он одной кнопкой сможет предупредить вас.",
 get:"Что вы будете получать:",g1:"Короткое предупреждение: «вашему близкому пришло подозрительное сообщение»",g2:"Ссылки, SMS-коды, номера карт НИКОГДА не пересылаются",g3:"Сообщение отправляет только сам близкий, нажав кнопку",
 yes:"✅ Согласен(на)",no:"✖ Отказаться",okT:"Спасибо! Код подтверждения:",okD:"Отправьте этот код близкому (ответьте SMS или продиктуйте). Когда он введёт код в CyberQalqan, связь станет активной.",
 copy:"📋 Скопировать код",copied:"Скопировано ✔",reply:"📨 Ответить SMS",replyMsg:"Код подтверждения CyberQalqan: {c}",noT:"Вы отказались",noD:"Связь не создана. Сообщите близкому, что вы отказались — он удалит вас из списка.",
 bad:"Ссылка-приглашение неверная или неполная. Попросите близкого отправить её снова.",me:"Ваш близкий",
 rev:"Если позже захотите отказаться, скажите близкому: он удалит вас в разделе «Семейный щит» одной кнопкой."}};
const q=new URLSearchParams(location.hash.slice(1));
const m=/^([\w-]{6,40})\.([\w-]{16,64})$/.exec(q.get("i")||"");
let L=q.get("l")==="ru"?"ru":"kk";
try{ const s=localStorage.getItem("cq_lang"); if(!q.get("l")&&(s==="ru"||s==="kk")) L=s; }catch(e){}
const name=(q.get("n")||"").replace(/[<>{}]/g,"").slice(0,40);
let view="ask";
const card=document.getElementById("card");
const el=(t,c,x)=>{const e=document.createElement(t);if(c)e.className=c;if(x!=null)e.textContent=x;return e;};
const t=(k,v)=>{let s=T[L][k];if(v)for(const x in v)s=s.split("{"+x+"}").join(v[x]);return s;};
const shield='<svg viewBox="0 0 24 28" aria-hidden="true"><path d="M12 1l10 3.6v7.6c0 6.6-4.3 12.3-10 14.3C6.3 24.5 2 18.8 2 12.2V4.6L12 1z" fill="rgba(91,140,255,.2)" stroke="#5b8cff" stroke-width="1.5"/><path d="M7.5 13.5l3 3 6-6.5" fill="none" stroke="#a9c8ff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
const isIOS=/iPhone|iPad|iPod/.test(navigator.userAgent);
function render(){
 document.documentElement.lang=L==="ru"?"ru":"kk";
 document.querySelectorAll(".lang button").forEach(b=>b.classList.toggle("on",b.dataset.l===L));
 card.replaceChildren();
 const v=el("div","viz"); v.innerHTML=shield; card.append(v);
 if(!m){ card.append(el("p","err",t("bad"))); return; }
 if(view==="ask"){
  card.append(el("h1",null,t("t",{n:name||t("me")})), el("p",null,t("d")), el("p",null,t("get")));
  const ul=el("ul"); ["g1","g2","g3"].forEach(k=>ul.append(el("li",null,t(k)))); card.append(ul);
  const y=el("button","btn yes",t("yes")); y.type="button"; y.addEventListener("click",()=>{view="ok";render();});
  const n=el("button","btn no",t("no")); n.type="button"; n.addEventListener("click",()=>{view="no";render();});
  card.append(y,n);
 } else if(view==="ok"){
  const code=famCode(m[1],m[2]);
  card.append(el("h1",null,t("okT")), el("div","code",code.slice(0,3)+" "+code.slice(3)), el("p",null,t("okD")));
  const c=el("button","btn yes",t("copy")); c.type="button";
  c.addEventListener("click",async()=>{ try{ await navigator.clipboard.writeText(code); c.textContent=t("copied"); }catch(e){} });
  const r=el("a","btn no",t("reply")); r.href="sms:"+(isIOS?"&":"?")+"body="+encodeURIComponent(t("replyMsg",{c:code}));
  card.append(c,r, el("p","mut",t("rev")));
 } else {
  card.append(el("h1",null,t("noT")), el("p",null,t("noD")));
 }
}
document.querySelectorAll(".lang button").forEach(b=>b.addEventListener("click",()=>{L=b.dataset.l;try{localStorage.setItem("cq_lang",L);}catch(e){}render();}));
render();
})();
