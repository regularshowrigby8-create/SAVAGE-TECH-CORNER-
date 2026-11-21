const {appScript}=require('./tests/app-script.cjs');
const lines=appScript().split('\n');

let inBlock=false;
const cleaned=lines.map(line=>{
  let out='',i=0;
  while(i<line.length){
    if(inBlock){ const e=line.indexOf('*/',i); if(e<0){i=line.length;} else {inBlock=false;i=e+2;} continue; }
    if(line.startsWith('/*',i)){ inBlock=true; i+=2; continue; }
    if(line.startsWith('//',i)) break;
    const ch=line[i];
    if(ch==='"'||ch==="'"||ch==='`'){
      const q=ch; i++;
      while(i<line.length){ if(line[i]==='\\'){i+=2;continue;} if(line[i]===q){i++;break;} i++; }
      out+='X'; continue;
    }
    out+=ch; i++;
  }
  return out;
});
let depth=0; const depthAt=[];
for(let i=0;i<cleaned.length;i++){ depthAt[i]=depth; for(const ch of cleaned[i]){ if(ch==='{')depth++; else if(ch==='}')depth=Math.max(0,depth-1); } }

const decls=[];
lines.forEach((l,i)=>{ const m=l.match(/^(?:const|let)\s+([A-Za-z_$][\w$]*)\s*=/); if(m)decls.push({name:m[1],line:i+1}); });

function usedOn(text,name){
  // identifier not preceded/followed by an identifier char, and not a property key (name:)
  const esc=name.replace(/[$]/g,'\\$');
  const re=new RegExp('(^|[^\\w$])'+esc+'(?![\\w$])','g');
  let m;
  while((m=re.exec(text))){
    const after=text.slice(m.index+m[0].length);
    // `settings:` inside an object literal is a key, not a read
    if(/^\s*:/.test(after)) continue;
    return true;
  }
  return false;
}
const bad=[];
for(const d of decls){
  for(let i=0;i<d.line-1;i++){
    if(depthAt[i]!==0) continue;
    if(!cleaned[i].trim()) continue;
    if(usedOn(cleaned[i],d.name)){ bad.push(d.name+' used@'+(i+1)+' declared@'+d.line); break; }
  }
}
console.log('declarations:',decls.length,'| offenders:',bad.length);
bad.forEach(b=>console.log('  '+b));
