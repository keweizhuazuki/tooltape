'use strict';
// Dependency-free interaction checks using a minimal in-memory DOM model.
// This is NOT a browser rendering, accessibility, or screenshot test.
// Run from any directory: node path/to/viewer/test-interactions.cjs
const path = require('node:path');
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const html=fs.readFileSync(path.join(__dirname, 'index.html'),'utf8'),script=html.match(/<script>([\s\S]*?)<\/script>/)[1];
class Node {
 constructor(tag){this.tag=tag;this.children=[];this.listeners={};this.attributes={};this.dataset={};this.style={};this.value='';this.checked=false;this.hidden=false;this.className='';this._text='';this.classList={toggle:(name,on)=>{const parts=new Set(this.className.split(' ').filter(Boolean));if(on)parts.add(name);else parts.delete(name);this.className=[...parts].join(' ');},add:name=>this.classList.toggle(name,true),remove:name=>this.classList.toggle(name,false)};}
 set textContent(x){this._text=String(x);this.children=[];}get textContent(){return this._text+this.children.map(n=>n.textContent).join('');}
 set innerHTML(x){throw Error('Unsafe innerHTML rendering');}append(...nodes){for(const n of nodes){if(n.tag==='#fragment')this.children.push(...n.children);else this.children.push(n);}}replaceChildren(...nodes){this._text='';this.children=[];this.append(...nodes);}
 setAttribute(k,v){this.attributes[k]=v;}addEventListener(k,fn){(this.listeners[k]??=[]).push(fn);}fire(k,event={}){for(const fn of this.listeners[k]||[])fn(event);}click(){if(!this.disabled)this.fire('click');}focus(options){if(!this.disabled){document.activeElement=this;this.focusOptions=options;}}scrollIntoView(options){this.scrollOptions=options;this.scrolls=(this.scrolls||0)+1;}
 descendants(){return this.children.flatMap(c=>[c,...c.descendants()]);}querySelectorAll(q){if(q==='button[data-position]')return this.descendants().filter(n=>n.tag==='button'&&n.dataset.position!==undefined);return [];}querySelector(q){if(q==='button[data-position]')return this.querySelectorAll(q)[0]||null;let m=q.match(/\[data-position="(\d+)"\]/);return m?this.descendants().find(n=>n.dataset.position===m[1]):null;}
}
const nodes={};for(const m of html.matchAll(/id="([^"]+)"/g))nodes[m[1]]=new Node('div');
const document=new Node('document');document.body=new Node('body');document.activeElement=document.body;document.getElementById=x=>nodes[x];document.createElement=tag=>new Node(tag);document.createDocumentFragment=()=>new Node('#fragment');
const ctx={TextEncoder,TextDecoder,Uint8Array,atob,btoa,document,console};vm.createContext(ctx);vm.runInContext(script,ctx);
const run=x=>vm.runInContext(x,ctx);let checks=0;const check=f=>{f();checks++};
const file=(text,name='test.tape')=>{let bytes=new TextEncoder().encode(text);return{name,size:bytes.length,arrayBuffer:async()=>bytes.buffer}};
// Apply the structural budget to every timeline replacement, including filtered
// resets and comparison jumps. This measures the DOM model, not browser latency.
const replaceTimeline=nodes.timeline.replaceChildren;
let timelineRenders=0;
nodes.timeline.replaceChildren=function(...children){
 replaceTimeline.apply(this,children);
 check(()=>assert.ok(this.querySelectorAll('button[data-position]').length<=250,'Timeline must render at most 250 call buttons'));
 check(()=>assert.ok(this.descendants().length<=2750,'Timeline must render at most 2,750 row elements'));
 timelineRenders++;
};
(async()=>{
 // Static semantics complement the DOM model, which does not parse HTML attributes
 // or emulate Tab order, native key activation, layout, or visible focus rendering.
 const shortcuts=html.match(/<div class="timeline-shortcuts"[^>]*>[\s\S]*?<\/div>\s*<ol[^>]*id="timeline"/);
 check(()=>assert.ok(shortcuts,'The shortcut group must immediately precede the native call list'));
 check(()=>assert.match(shortcuts[0],/role="group" aria-label="Timeline shortcuts"/));
 check(()=>assert.match(shortcuts[0],/<button[^>]*id="skip-pages"[^>]*type="button"[^>]*aria-controls="timeline-pagination"[^>]*hidden>Skip to page controls<\/button>/));
 check(()=>assert.match(shortcuts[0],/<button[^>]*id="skip-details"[^>]*type="button"[^>]*aria-controls="inspector">Skip to call details<\/button>/));
 check(()=>assert.ok(shortcuts[0].indexOf('id="skip-pages"')<shortcuts[0].indexOf('id="skip-details"')));
 check(()=>assert.match(html,/<section[^>]*id="inspector"[^>]*aria-label="Selected call"[^>]*tabindex="-1"[^>]*hidden>/));
 check(()=>assert.match(html,/\.inspector:focus\{outline:2px solid var\(--lime\);outline-offset:-3px\}/));
 nodes['load-example'].click();check(()=>assert.equal(nodes['stat-calls'].textContent,'2'));check(()=>assert.equal(nodes['stat-errors'].textContent,'1'));check(()=>assert.equal(nodes['stat-success'].textContent,'50'));check(()=>assert.equal(nodes.timeline.querySelectorAll('button[data-position]').length,2));
 nodes.timeline.querySelector('[data-position="1"]').click();check(()=>assert.ok(nodes.inspector.textContent.includes('temperature')));
 nodes['errors-only'].checked=true;nodes['errors-only'].fire('change');check(()=>assert.equal(nodes.timeline.querySelectorAll('button[data-position]').length,1));
 nodes.search.value='missing';nodes.search.fire('input');check(()=>assert.ok(nodes.timeline.textContent.includes('No calls')));document.fire('keydown',{key:'Escape'});check(()=>assert.equal(nodes.timeline.querySelectorAll('button[data-position]').length,2));
 await ctx.readFile(file(ctx.exampleTape().replace('48685','1'),'same.tape'),true);check(()=>assert.ok(nodes['compare-title'].textContent.includes('all 2 calls match')));
 await ctx.readFile(file(ctx.exampleTape().replace(ctx.encode('{"temperature":22}'),ctx.encode('{"temperature":23}')),'drift.tape'),true);check(()=>assert.ok(nodes['compare-title'].textContent.includes('position 2')));nodes['jump-divergence'].click();check(()=>assert.ok(nodes.inspector.textContent.includes('COMPARISON OUTPUT')));check(()=>assert.ok(nodes.inspector.textContent.includes('23')));
 nodes['clear-compare'].click();check(()=>assert.equal(nodes.comparison.hidden,true));
 await ctx.readFile(file('invalid','bad.tape'),false);check(()=>assert.equal(nodes.notice.hidden,false));check(()=>assert.equal(nodes['stat-calls'].textContent,'2'));
 const payload='{"message":"<img src=x onerror=alert(1)>"}';const tape='TOOLTAPE\t1\n0\t1\t'+ctx.encode('test.run')+'\t'+ctx.encode(payload)+'\tOK\t'+ctx.encode('{}')+'\n';
 await ctx.readFile(file(tape,'<svg onload=alert(1)>.tape'),false);check(()=>assert.ok(nodes.inspector.textContent.includes('<img src=x onerror=alert(1)>')));check(()=>assert.ok(nodes.filename.textContent.includes('<svg')));check(()=>assert.ok(nodes.inspector.descendants().every(n=>!['img','script','svg'].includes(n.tag))));
 await ctx.readFile(file('TOOLTAPE\t1\n','empty.tape'),false);check(()=>assert.equal(nodes['stat-calls'].textContent,'0'));await ctx.readFile(file(ctx.exampleTape()),true);nodes['jump-divergence'].click();check(()=>assert.ok(nodes.inspector.textContent.includes('missing from the primary')));check(()=>assert.ok(nodes.inspector.textContent.includes('COMPARISON INPUT')));
 let release;const old={name:'old.tape',size:100,arrayBuffer:()=>new Promise(r=>release=r)};const pending=ctx.readFile(old,false);await ctx.readFile(file(tape,'new.tape'),false);release(new TextEncoder().encode(ctx.exampleTape()).buffer);await pending;check(()=>assert.equal(nodes.filename.textContent,'new.tape'));
 await ctx.readFile({name:'big.tape',size:16*1024*1024+1,arrayBuffer:()=>{throw Error('Should not read oversized file')}},false);check(()=>assert.ok(nodes.notice.textContent.includes('16 MiB')));
 // v2 details are visible without claiming business success or authenticating provenance.
 const policyId='sha256:'+'a'.repeat(64);
 const v2=(source='source.static',policy=policyId,complete=true,body=tape.split('\n').slice(1,-1).join('\n'))=>'TOOLTAPE\t2\nMETA\t'+ctx.encode(JSON.stringify({policyId:policy,source}))+'\n'+(body?body+'\n':'')+(complete?'END\t'+(body?body.split('\n').length:0)+'\n':'');
 await ctx.readFile(file(v2(),'closed-v2.tape'),false);
 check(()=>assert.equal(nodes.recording.hidden,false));
 check(()=>assert.equal(nodes['recording-schema'].textContent,'TOOLTAPE v2'));
 check(()=>assert.equal(nodes['recording-completeness'].textContent,'SESSION CLOSED'));
 check(()=>assert.equal(nodes['recording-source'].textContent,'source.static'));
 check(()=>assert.equal(nodes['recording-policy'].textContent,'sha256:aaaaaaaaaaaa…'));
 check(()=>assert.equal(nodes['recording-policy'].title,policyId));
 check(()=>assert.equal(nodes['recording-policy'].attributes['aria-label'],'Capture policy '+policyId));
 check(()=>assert.match(nodes['recording-note'].textContent,/does not prove business success/));
 check(()=>assert.equal(nodes.notice.hidden,true));
 await ctx.readFile(file(v2('another.source'),'same-policy.tape'),true);
 check(()=>assert.match(nodes['compare-title'].textContent,/all 1 calls match/));
 check(()=>assert.equal(nodes['compare-policy-warning'].hidden,true));
 check(()=>assert.match(nodes['compare-context'].textContent,/another.source/));
 check(()=>assert.match(nodes['compare-context'].textContent,/SESSION CLOSED/));
 await ctx.readFile(file(v2('another.source','sha256:'+'b'.repeat(64)),'different-policy.tape'),true);
 check(()=>assert.match(nodes['compare-title'].textContent,/all 1 calls match/));
 check(()=>assert.equal(nodes['jump-divergence'].hidden,true));
 check(()=>assert.equal(nodes['compare-policy-warning'].hidden,false));
 check(()=>assert.match(nodes['compare-policy-warning'].textContent,/Different capture policies/));
 check(()=>assert.match(nodes['compare-context'].textContent,/sha256:bbbbbbbbbbbb…/));
 check(()=>assert.equal(nodes['compare-context'].title,'Capture policy sha256:'+'b'.repeat(64)));
 await ctx.readFile(file(v2('source.static',policyId,false),'incomplete-compare.tape'),true);
 check(()=>assert.match(nodes['compare-context'].textContent,/INCOMPLETE/));
 check(()=>assert.match(nodes['compare-context'].textContent,/cannot be used for safe replay/));
 check(()=>assert.match(nodes['compare-title'].textContent,/all 1 calls match/));
 check(()=>assert.equal(nodes['compare-policy-warning'].hidden,true));
 check(()=>assert.match(nodes.announcer.textContent,/cannot be used for safe replay/));
 await ctx.readFile(file(v2('source.static',policyId,false),'incomplete-v2.tape'),false);
 check(()=>assert.equal(nodes['recording-completeness'].textContent,'INCOMPLETE'));
 check(()=>assert.match(nodes['recording-note'].textContent,/cannot be used for safe replay/));
 check(()=>assert.match(nodes.recording.className,/incomplete/));
 check(()=>assert.equal(nodes.notice.hidden,true));
 check(()=>assert.equal(nodes['stat-calls'].textContent,'1'));
 await ctx.readFile(file(v2('<img src=x onerror=alert(1)>'),'bad-source.tape'),false);
 check(()=>assert.match(nodes.notice.textContent,/source must be a static identifier/));
 check(()=>assert.equal(nodes.filename.textContent,'incomplete-v2.tape'));
 check(()=>assert.equal(nodes['recording-source'].textContent,'source.static'));
 check(()=>assert.ok(Object.values(nodes).every(n=>n.descendants().every(c=>!['img','script','svg'].includes(c.tag)))));
 await ctx.readFile(file(v2('a'.repeat(128),policyId,true,''),'empty-v2.tape'),false);
 check(()=>assert.equal(nodes['recording-source'].textContent.length,128));
 check(()=>assert.equal(nodes['recording-completeness'].textContent,'SESSION CLOSED'));
 check(()=>assert.match(nodes['recording-note'].textContent,/0 calls/));
 check(()=>assert.equal(nodes['stat-calls'].textContent,'0'));
 check(()=>assert.doesNotMatch(nodes.recording.className,/incomplete/));
 await ctx.readFile(file(tape,'legacy.tape'),false);
 check(()=>assert.equal(nodes['recording-schema'].textContent,'TOOLTAPE v1'));
 check(()=>assert.equal(nodes['recording-completeness'].textContent,'LEGACY · UNKNOWN'));
 check(()=>assert.equal(nodes['recording-source'].textContent,'Not recorded'));
 check(()=>assert.equal(nodes['recording-policy'].textContent,'Not recorded'));
 check(()=>assert.match(nodes['recording-note'].textContent,/completeness is unknown/));
 await ctx.readFile(file(v2(),'v2-compare.tape'),true);
 check(()=>assert.match(nodes['compare-policy-warning'].textContent,/unavailable for a legacy tape/));
 check(()=>assert.match(nodes['compare-title'].textContent,/all 1 calls match/));
 // Interrupted comparison reads cannot attach to a newer primary or resurrect a cleared comparison.
 let releaseCompare;const delayedCompare={name:'stale-compare.tape',size:100,arrayBuffer:()=>new Promise(r=>releaseCompare=r)};
 let pendingCompare=ctx.readFile(delayedCompare,true);await ctx.readFile(file(v2(),'new-primary.tape'),false);
 releaseCompare(new TextEncoder().encode(v2()).buffer);await pendingCompare;
 check(()=>assert.equal(nodes.comparison.hidden,true));
 pendingCompare=ctx.readFile(delayedCompare,true);nodes['clear-compare'].click();
 releaseCompare(new TextEncoder().encode(v2()).buffer);await pendingCompare;
 check(()=>assert.equal(nodes.comparison.hidden,true));
 pendingCompare=ctx.readFile(delayedCompare,true);await ctx.readFile(file(v2('new.source'),'new-compare.tape'),true);
 releaseCompare(new TextEncoder().encode(v2()).buffer);await pendingCompare;
 check(()=>assert.match(nodes['compare-note'].textContent,/new-compare.tape/));
 check(()=>assert.match(nodes['compare-context'].textContent,/new.source/));
 // Exercise notice ownership through ordinary file-input changes, Clear comparison,
 // and drops. Deferred file reads control ordering without replacing application code.
 const settleFileRead=()=>new Promise(resolve=>setImmediate(resolve));
 const chooseFile=async(input,selectedFile)=>{
  nodes[input].files=[selectedFile];nodes[input].value=selectedFile.name;
  nodes[input].fire('change',{target:nodes[input]});
  check(()=>assert.equal(nodes[input].value,''));
  await settleFileRead();
 };
 const deferredFile=name=>{
  let resolve,reject;
  const bytes=new Promise((yes,no)=>{resolve=yes;reject=no;});
  return {file:{name,size:100,arrayBuffer:()=>bytes},
   finish:async text=>{resolve(new TextEncoder().encode(text).buffer);await settleFileRead();},
   fail:async()=>{reject(new Error('File read failed.'));await settleFileRead();}};
 };
 const noticeTape='TOOLTAPE\t1\n0\t1\tdGVzdC5ydW4\te30\tOK\te30\n';
 const truncatedNoticeTape=noticeTape.slice(0,-1);
 const noticeMessage=name=>'Could not open '+name+'\nTruncated tape: a final LF newline is required.';
 const expectNotice=message=>{
  check(()=>assert.equal(nodes.notice.hidden,false));
  check(()=>assert.equal(nodes.notice.textContent,message));
 };
 const expectNoNotice=()=>{
  check(()=>assert.equal(nodes.notice.hidden,true));
  check(()=>assert.equal(nodes.notice.textContent,''));
 };
 const loadNoticePair=async()=>{
  await chooseFile('open-file',file(noticeTape,'notice-primary.tape'));
  await chooseFile('compare-file',file(noticeTape,'notice-comparison.tape'));
  check(()=>assert.equal(nodes.comparison.hidden,false));
  expectNoNotice();
 };
 const dropMultiple=()=>document.fire('drop',{preventDefault(){},dataTransfer:{files:[file(noticeTape),file(noticeTape)]}});
 const dropMessage='Drop one .tape file at a time. Use Compare tape to load a second run.';
 // A failed comparison replacement preserves the valid pair until Clear comparison.
 await loadNoticePair();
 await chooseFile('compare-file',file(truncatedNoticeTape,'truncated-comparison.tape'));
 expectNotice(noticeMessage('truncated-comparison.tape'));
 check(()=>assert.equal(nodes.filename.textContent,'notice-primary.tape'));
 check(()=>assert.match(nodes['compare-note'].textContent,/notice-comparison.tape/));
 nodes['clear-compare'].focus();nodes['clear-compare'].click();
 expectNoNotice();
 check(()=>assert.equal(nodes.comparison.hidden,true));
 check(()=>assert.equal(document.activeElement,nodes['compare-button']));
 check(()=>assert.match(nodes.inspector.textContent,/test\.run/));
 check(()=>assert.equal(nodes.announcer.textContent,'Comparison cleared.'));
 // A new primary discards errors belonging to the previously visible comparison,
 // including a failed comparison selected while that primary was still reading.
 await loadNoticePair();
 const slowPrimary=deferredFile('slow-primary.tape');
 await chooseFile('open-file',slowPrimary.file);
 await chooseFile('compare-file',file(truncatedNoticeTape,'late-bad-comparison.tape'));
 expectNotice(noticeMessage('late-bad-comparison.tape'));
 await slowPrimary.finish(noticeTape);
 expectNoNotice();
 check(()=>assert.equal(nodes.filename.textContent,'slow-primary.tape'));
 check(()=>assert.equal(nodes.comparison.hidden,true));
 // The reverse ordering must retain a newer primary failure after comparison
 // success, and after that successful comparison is explicitly cleared.
 await loadNoticePair();
 const slowComparison=deferredFile('slow-comparison.tape');
 await chooseFile('compare-file',slowComparison.file);
 await chooseFile('open-file',file(truncatedNoticeTape,'bad-primary.tape'));
 expectNotice(noticeMessage('bad-primary.tape'));
 await slowComparison.finish(noticeTape);
 expectNotice(noticeMessage('bad-primary.tape'));
 check(()=>assert.match(nodes['compare-note'].textContent,/slow-comparison.tape/));
 check(()=>assert.equal(nodes.filename.textContent,'notice-primary.tape'));
 nodes['clear-compare'].click();
 expectNotice(noticeMessage('bad-primary.tape'));
 check(()=>assert.equal(nodes.comparison.hidden,true));
 // A general drop notice can replace a comparison notice. Later primary success
 // or Clear comparison must not mistake it for the superseded comparison failure.
 await loadNoticePair();
 const primaryBeforeDrop=deferredFile('primary-before-drop.tape');
 await chooseFile('open-file',primaryBeforeDrop.file);
 await chooseFile('compare-file',file(truncatedNoticeTape,'before-drop.tape'));
 expectNotice(noticeMessage('before-drop.tape'));
 dropMultiple();expectNotice(dropMessage);
 nodes['clear-compare'].click();expectNotice(dropMessage);
 await primaryBeforeDrop.finish(noticeTape);
 expectNotice(dropMessage);
 check(()=>assert.equal(nodes.filename.textContent,'primary-before-drop.tape'));
 check(()=>assert.equal(nodes.comparison.hidden,true));
 await loadNoticePair();
 const comparisonBeforeDrop=deferredFile('comparison-before-drop.tape');
 await chooseFile('compare-file',comparisonBeforeDrop.file);
 dropMultiple();expectNotice(dropMessage);
 await comparisonBeforeDrop.finish(noticeTape);
 expectNotice(dropMessage);
 check(()=>assert.match(nodes['compare-note'].textContent,/comparison-before-drop.tape/));
 nodes['clear-compare'].click();expectNotice(dropMessage);
 // Clearing a visible pair cancels a pending replacement. Its later rejection
 // cannot resurrect a notice or replace a newer primary/drop failure.
 for(const newerNotice of ['none','primary','drop']) {
  await loadNoticePair();
  const clearedComparison=deferredFile('cleared-comparison.tape');
  await chooseFile('compare-file',clearedComparison.file);
  nodes['clear-compare'].click();expectNoNotice();
  if(newerNotice==='primary')await chooseFile('open-file',file(truncatedNoticeTape,'newer-primary.tape'));
  if(newerNotice==='drop')dropMultiple();
  await clearedComparison.fail();
  if(newerNotice==='none')expectNoNotice();
  else expectNotice(newerNotice==='primary'?noticeMessage('newer-primary.tape'):dropMessage);
  check(()=>assert.equal(nodes.comparison.hidden,true));
 }
 // A successful primary also invalidates pending comparison errors.
 await loadNoticePair();
 const replacedComparison=deferredFile('replaced-comparison.tape');
 await chooseFile('compare-file',replacedComparison.file);
 await chooseFile('open-file',file(noticeTape,'replacement-with-no-error.tape'));
 await replacedComparison.fail();expectNoNotice();
 check(()=>assert.equal(nodes.filename.textContent,'replacement-with-no-error.tape'));
 check(()=>assert.equal(nodes.comparison.hidden,true));
 // Read-start clearing and the primary generation guard retain their old behavior:
 // stale primary success/failure cannot hide a newer failed primary replacement.
 for(const outcome of ['success','failure']) {
  await loadNoticePair();
  dropMultiple();expectNotice(dropMessage);
  const stalePrimary=deferredFile('stale-primary.tape');
  await chooseFile('open-file',stalePrimary.file);expectNoNotice();
  await chooseFile('open-file',file(truncatedNoticeTape,'latest-bad-primary.tape'));
  if(outcome==='success')await stalePrimary.finish(noticeTape);else await stalePrimary.fail();
  expectNotice(noticeMessage('latest-bad-primary.tape'));
  check(()=>assert.equal(nodes.filename.textContent,'notice-primary.tape'));
  check(()=>assert.equal(nodes.comparison.hidden,false));
  nodes['clear-compare'].click();expectNotice(noticeMessage('latest-bad-primary.tape'));
 }
 // Page navigation never changes the inspector selection or accumulates timeline rows.
 const calls=()=>nodes.timeline.querySelectorAll('button[data-position]');
 const callAt=position=>nodes.timeline.querySelector('[data-position="'+position+'"]');
 const escape=()=>document.fire('keydown',{key:'Escape'});
 const filter=query=>{nodes.search.value=query;nodes.search.fire('input');};
 // A skip route changes only focus/scroll: no row rendering, call selection,
 // filters, page change, inspector replacement, or tape-derived announcement.
 const skipTo=(id,target)=>{
  const before={selection:run('state.selected'),page:run('state.pageStart'),query:nodes.search.value,errors:nodes['errors-only'].checked,
   inspector:nodes.inspector.children.slice(),rows:calls(),renders:timelineRenders,announcement:nodes.announcer.textContent,scrolls:target.scrolls||0};
  nodes[id].focus();nodes[id].click();
  check(()=>assert.equal(document.activeElement,target));
  check(()=>assert.equal(target.scrolls,before.scrolls+1));
  check(()=>assert.equal(target.scrollOptions.block,'nearest'));
  check(()=>assert.equal(target.focusOptions.preventScroll,true));
  check(()=>assert.equal(run('state.selected'),before.selection));
  check(()=>assert.equal(run('state.pageStart'),before.page));
  check(()=>assert.equal(nodes.search.value,before.query));
  check(()=>assert.equal(nodes['errors-only'].checked,before.errors));
  check(()=>assert.deepEqual(nodes.inspector.children,before.inspector));
  check(()=>assert.deepEqual(calls(),before.rows));
  check(()=>assert.equal(timelineRenders,before.renders));
  check(()=>assert.equal(nodes.announcer.textContent,before.announcement));
 };
 const skipDetails=()=>skipTo('skip-details',nodes.inspector);
 const normalTape=rows=>'TOOLTAPE\t1\n'+(rows.length?rows.join('\n')+'\n':'');
 const makeRows=rows=>rows.map((row,index)=>[index,'1',ctx.encode(row.name||'test.run'),ctx.encode(row.input||'{}'),row.status||'OK',ctx.encode(row.output||'{}')].join('\t'));
 // Search uses once-decoded JSON characters as well as original canonical text.
 // These ordinary files exercise the public filter; no helper or state is replaced.
 const unicodeCases=[
  {name:'unicode.bmp',input:JSON.stringify({'城市':'杭州'}),output:JSON.stringify({answer:'CAFÉ'})},
  {name:'unicode.emoji',input:JSON.stringify({route:'🚀 👩‍💻'}),output:JSON.stringify({'🧭':'🌍'})},
  {name:'unicode.literal',input:JSON.stringify({literal:String.raw`\ud83d\ude80`})},
  {name:'unicode.escaped',input:String.raw`{"message":"\uD83D\uDE80"}`,output:String.raw`{"city":"\u6771\u4eac"}`},
  {name:'unicode.case',input:JSON.stringify({word:'ÉCOLE',letter:'𐐀'}),output:JSON.stringify({word:'Straße'})},
  {name:'unicode.composed',input:JSON.stringify({value:'café'})},
  {name:'unicode.decomposed',input:JSON.stringify({value:'cafe\u0301'})},
  {name:'unicode.numbers',input:'{"exact":9223372036854775807,"marker":"🚀"}',output:'{"exact":9223372036854775806}'},
  {name:'unicode.key',input:JSON.stringify({'🔍':'value'})}
 ];
 const unicodeTape=normalTape(makeRows(unicodeCases));
 await ctx.readFile(file(unicodeTape,'unicode-search.tape'),false);
 check(()=>assert.equal(nodes.notice.hidden,true));
 check(()=>assert.equal(calls().length,9));
 const canonicalBefore=run('JSON.stringify(state.tape.calls,(_,v)=>typeof v==="bigint"?v.toString():v)');
 for(const [query,positions] of [
  ['城市',[0]],['杭州',[0]],['東京',[3]],['🚀',[1,3,7]],['👩‍💻',[1]],['🧭',[1]],['🌍',[1]],['🔍',[8]],
  [String.raw`"route":"\uD83D\uDE80`,[1]],
  [String.raw`"literal":"\\ud83d\\ude80"`,[2]],
  ['école',[4]],['𐐨',[4]],['straße',[4]],['STRASSE',[]],
  ['café',[0,5]],['cafe\u0301',[6]],
  ['9223372036854775807',[7]],['9223372036854775806',[7]],
  ['9223372036854775807,"marker":"🚀"',[7]],['9223372036854776000',[]]
 ]) {
  filter(query);
  check(()=>assert.deepEqual(calls().map(button=>Number(button.dataset.position)),positions,'Unicode search: '+JSON.stringify(query)));
  check(()=>assert.equal(nodes['visible-count'].textContent,(positions.length?'1–'+positions.length:'0')+' of '+positions.length+' matching calls'));
 }
 filter('🚀');callAt(1).click();
 check(()=>assert.ok(nodes.inspector.textContent.includes(String.raw`\ud83d\ude80`)));
 check(()=>assert.ok(!nodes.inspector.textContent.includes('🚀')));
 check(()=>assert.equal(run('JSON.stringify(state.tape.calls,(_,v)=>typeof v==="bigint"?v.toString():v)'),canonicalBefore));
 const equivalentUnicode=unicodeCases.map((row,index)=>index===1?{...row,input:String.raw`{"route":"\ud83d\ude80 \ud83d\udc69\u200d\ud83d\udcbb"}`}:row);
 await ctx.readFile(file(normalTape(makeRows(equivalentUnicode)),'unicode-equivalent.tape'),true);
 check(()=>assert.match(nodes['compare-title'].textContent,/all 9 calls match/));
 nodes['clear-compare'].click();
 // 501 literal-emoji matches and 251 matching errors cross the page boundary.
 // Nonmatching calls contain literal backslash-u text, so double decoding would
 // also corrupt the asserted totals and page positions throughout this flow.
 const unicodeMany=Array.from({length:751},(_,index)=>({
  name:'unicode.step.'+index,
  input:JSON.stringify({message:index%3===2?String.raw`\ud83d\ude80`:'杭州 🚀 👩‍💻',sequence:index}),
  status:index%3===1?'OK':'ERROR',output:index%3===1?'{}':'java.io.IOException'
 }));
 await ctx.readFile(file(normalTape(makeRows(unicodeMany)),'unicode-pages.tape'),false);
 const unicodePage=(range,first,last,count)=>{
  check(()=>assert.equal(nodes['visible-count'].textContent,range));
  check(()=>assert.equal(calls().length,count));
  if(count){check(()=>assert.equal(calls()[0],callAt(first)));check(()=>assert.equal(calls()[count-1],callAt(last)));}
 };
 filter('🚀');unicodePage('1–250 of 501 matching calls',0,373,250);
 nodes['next-page'].click();unicodePage('251–500 of 501 matching calls',375,748,250);
 check(()=>assert.equal(document.activeElement,callAt(375)));
 check(()=>assert.match(nodes.inspector.textContent,/unicode\.step\.0/));
 nodes['next-page'].click();unicodePage('501–501 of 501 matching calls',750,750,1);
 check(()=>assert.equal(document.activeElement,callAt(750)));
 check(()=>assert.equal(callAt(750).attributes['aria-pressed'],'false'));
 callAt(750).click();
 nodes['previous-page'].click();unicodePage('251–500 of 501 matching calls',375,748,250);
 check(()=>assert.equal(document.activeElement,callAt(375)));
 check(()=>assert.match(nodes.inspector.textContent,/unicode\.step\.750/));
 check(()=>assert.ok(calls().every(button=>button.attributes['aria-pressed']==='false')));
 filter('👩‍💻');unicodePage('1–250 of 501 matching calls',0,373,250);
 nodes['errors-only'].checked=true;nodes['errors-only'].fire('change');
 unicodePage('1–250 of 251 matching calls',0,747,250);
 nodes['next-page'].click();unicodePage('251–251 of 251 matching calls',750,750,1);
 check(()=>assert.equal(document.activeElement,callAt(750)));
 check(()=>assert.equal(callAt(750).attributes['aria-pressed'],'true'));
 nodes['errors-only'].checked=false;nodes['errors-only'].fire('change');
 unicodePage('1–250 of 501 matching calls',0,373,250);
 nodes['next-page'].click();callAt(748).focus();escape();
 unicodePage('501–750 of 751 matching calls',500,749,250);
 check(()=>assert.equal(document.activeElement,callAt(748)));
 check(()=>assert.match(nodes.inspector.textContent,/unicode\.step\.750/));
 check(()=>assert.ok(calls().every(button=>button.attributes['aria-pressed']==='false')));
 nodes['next-page'].click();unicodePage('751–751 of 751 matching calls',750,750,1);
 check(()=>assert.equal(document.activeElement,callAt(750)));
 check(()=>assert.equal(callAt(750).attributes['aria-pressed'],'true'));
 for(const control of ['search','errors-only']) {
  filter('🚀');nodes['errors-only'].checked=true;nodes['errors-only'].fire('change');
  unicodePage('1–250 of 251 matching calls',0,747,250);
  if(control==='search'){filter('no-unicode-match');unicodePage('0 of 0 matching calls',0,0,0);}
  nodes[control].focus();escape();
  unicodePage('751–751 of 751 matching calls',750,750,1);
  check(()=>assert.equal(nodes.search.value,''));
  check(()=>assert.equal(nodes['errors-only'].checked,false));
  check(()=>assert.equal(document.activeElement,nodes[control]));
  check(()=>assert.equal(callAt(750).attributes['aria-pressed'],'true'));
  check(()=>assert.match(nodes.inspector.textContent,/unicode\.step\.750/));
 }
 const normalRows=Array.from({length:501},(_,index)=>[index,'1',ctx.encode((index<499?'step.':'tail.')+index),ctx.encode('{}'),'OK',ctx.encode('{}')].join('\t'));
 for(const size of [0,1,250,251,500,501]) {
  await ctx.readFile(file(normalTape(normalRows.slice(0,size)),'pages-'+size+'.tape'),false);
  check(()=>assert.equal(calls().length,Math.min(250,size)));
  check(()=>assert.equal(nodes['timeline-pagination'].hidden,size<=250));
  check(()=>assert.equal(nodes['skip-pages'].hidden,size<=250));
  check(()=>assert.equal(nodes['timeline-pane'].hidden,false));
  check(()=>assert.equal(nodes['skip-details'].hidden,false));
  check(()=>assert.equal(nodes.inspector.hidden,false));
  skipDetails();
  if(size>250)skipTo('skip-pages',nodes['next-page']);
  else {
   // A hidden route cannot move focus to hidden/disabled footer controls.
   nodes.search.focus();nodes['skip-pages'].click();
   check(()=>assert.equal(document.activeElement,nodes.search));
  }
  if(!size)check(()=>assert.match(nodes.inspector.textContent,/Select a recorded call/));
  check(()=>assert.equal(nodes['previous-page'].disabled,true));
  check(()=>assert.equal(nodes['next-page'].disabled,size<=250));
  check(()=>assert.equal(nodes['visible-count'].textContent,(size?'1–'+Math.min(250,size):'0')+' of '+size+' matching calls'));
  const inspector=nodes.inspector.children[0];
  const visited=calls().map(button=>Number(button.dataset.position));
  while(!nodes['next-page'].disabled) {
   nodes['next-page'].click();
   check(()=>assert.ok(calls().length<=250));
   check(()=>assert.equal(document.activeElement,calls()[0]));
   check(()=>assert.equal(nodes.inspector.children[0],inspector));
   check(()=>assert.ok(calls().every(button=>button.attributes['aria-pressed']==='false')));
   visited.push(...calls().map(button=>Number(button.dataset.position)));
   skipTo('skip-pages',nodes['previous-page']);
   skipDetails();
  }
  check(()=>assert.deepEqual(visited,Array.from({length:size},(_,i)=>i)));
  // Disabled boundaries do not rebuild the timeline.
  const finalFirst=calls()[0];nodes['next-page'].click();
  check(()=>assert.equal(calls()[0],finalFirst));
  while(!nodes['previous-page'].disabled) {
   nodes['previous-page'].click();
   check(()=>assert.ok(calls().length<=250));
   check(()=>assert.equal(document.activeElement,calls()[0]));
   check(()=>assert.equal(nodes.inspector.children[0],inspector));
   skipTo('skip-pages',nodes['previous-page'].disabled?nodes['next-page']:nodes['previous-page']);
  }
  if(size)check(()=>assert.equal(calls()[0].attributes['aria-pressed'],'true'));
 }
 // Selection and keyboard focus can belong to different pages after filter reset.
 filter('tail.');callAt(500).click();callAt(499).focus();escape();
 check(()=>assert.equal(calls().length,250));
 check(()=>assert.equal(nodes['visible-count'].textContent,'251–500 of 501 matching calls'));
 check(()=>assert.equal(document.activeElement,callAt(499)));
 check(()=>assert.equal(callAt(500),undefined));
 check(()=>assert.match(nodes.inspector.textContent,/tail\.500/));
 check(()=>assert.ok(calls().every(button=>button.attributes['aria-pressed']==='false')));
 nodes['next-page'].click();
 check(()=>assert.equal(calls().length,1));
 check(()=>assert.equal(nodes['visible-count'].textContent,'501–501 of 501 matching calls'));
 check(()=>assert.equal(document.activeElement,callAt(500)));
 check(()=>assert.equal(callAt(500).attributes['aria-pressed'],'true'));
 const selectedNode=callAt(500),selectedInspector=nodes.inspector.children[0];escape();
 check(()=>assert.equal(callAt(500),selectedNode));
 check(()=>assert.equal(document.activeElement,selectedNode));
 check(()=>assert.equal(nodes.inspector.children[0],selectedInspector));
 // Both filter controls keep focus, with the selected call revealed on its own page.
 for(const control of ['search','errors-only']) {
  filter('tail.');nodes['errors-only'].checked=true;nodes['errors-only'].fire('change');
  check(()=>assert.equal(nodes['skip-pages'].hidden,true));
  skipDetails();
  nodes[control].focus();escape();
  check(()=>assert.equal(nodes.search.value,''));
  check(()=>assert.equal(nodes['errors-only'].checked,false));
  check(()=>assert.equal(document.activeElement,nodes[control]));
  check(()=>assert.equal(calls().length,1));
  check(()=>assert.equal(callAt(500).attributes['aria-pressed'],'true'));
 }
 // Empty results and repeated filter edits reset the page without losing selection.
 filter('absent');
 check(()=>assert.equal(calls().length,0));
 check(()=>assert.equal(nodes['visible-count'].textContent,'0 of 0 matching calls'));
 check(()=>assert.equal(nodes['timeline-pagination'].hidden,true));
 check(()=>assert.equal(nodes['skip-pages'].hidden,true));
 skipDetails();
 filter('step.');
 check(()=>assert.equal(nodes['visible-count'].textContent,'1–250 of 499 matching calls'));
 check(()=>assert.equal(nodes['previous-page'].disabled,true));
 check(()=>assert.match(nodes.inspector.textContent,/tail\.500/));
 check(()=>assert.equal(nodes['skip-pages'].hidden,false));
 skipTo('skip-pages',nodes['next-page']);
 skipDetails();
 // An available page control survives Escape; disabled/hidden controls move focus to a call.
 callAt(0).click();nodes['next-page'].focus();escape();
 check(()=>assert.equal(document.activeElement,nodes['next-page']));
 check(()=>assert.equal(nodes['visible-count'].textContent,'1–250 of 501 matching calls'));
 filter('step.');nodes['next-page'].click();nodes['previous-page'].focus();escape();
 check(()=>assert.equal(document.activeElement,callAt(0)));
 check(()=>assert.equal(nodes['previous-page'].disabled,true));
 filter('step.');nodes['next-page'].click();callAt(498).click();
 nodes['previous-page'].focus();escape();
 check(()=>assert.equal(document.activeElement,nodes['previous-page']));
 check(()=>assert.equal(nodes['visible-count'].textContent,'251–500 of 501 matching calls'));
 // Maximum valid recording: final divergence and Escape stay within one 250-call page.
 const largeRows=Array.from({length:50000},(_,index)=>[index,'1',ctx.encode('step.'+index),ctx.encode('{}'),'OK',ctx.encode('{}')].join('\t'));
 const large=normalTape(largeRows);
 await ctx.readFile(file(large,'large-normal.tape'),false);
 check(()=>assert.equal(calls().length,250));
 check(()=>assert.equal(nodes['visible-count'].textContent,'1–250 of 50000 matching calls'));
 const changedRows=largeRows.slice();changedRows[49999]=changedRows[49999].replace(/e30$/,ctx.encode('{"changed":true}'));
 await ctx.readFile(file(normalTape(changedRows),'large-normal-drift.tape'),true);
 check(()=>assert.match(nodes['compare-title'].textContent,/position 50000/));
 nodes['jump-divergence'].click();
 check(()=>assert.equal(calls().length,250));
 check(()=>assert.equal(nodes['visible-count'].textContent,'49751–50000 of 50000 matching calls'));
 check(()=>assert.equal(document.activeElement,callAt(49999)));
 check(()=>assert.equal(callAt(49999).attributes['aria-pressed'],'true'));
 check(()=>assert.match(nodes.inspector.textContent,/COMPARISON OUTPUT/));
 check(()=>assert.match(nodes.inspector.textContent,/changed/));
 check(()=>assert.equal(nodes['next-page'].disabled,true));
 check(()=>assert.equal(nodes['previous-page'].disabled,false));
 const lastNode=callAt(49999);escape();
 check(()=>assert.equal(callAt(49999),lastNode));
 check(()=>assert.equal(document.activeElement,lastNode));
 filter('step.49999');nodes.search.focus();escape();
 check(()=>assert.equal(calls().length,250));
 check(()=>assert.equal(document.activeElement,nodes.search));
 check(()=>assert.equal(callAt(49999).attributes['aria-pressed'],'true'));
 filter('step.1');nodes['jump-divergence'].click();
 check(()=>assert.equal(nodes.search.value,''));
 check(()=>assert.equal(calls().length,250));
 check(()=>assert.equal(document.activeElement,callAt(49999)));
 nodes['previous-page'].click();
 check(()=>assert.equal(calls().length,250));
 check(()=>assert.equal(nodes['visible-count'].textContent,'49501–49750 of 50000 matching calls'));
 check(()=>assert.match(nodes.inspector.textContent,/step\.49999/));
 nodes['next-page'].click();
 check(()=>assert.equal(callAt(49999).attributes['aria-pressed'],'true'));
 nodes['clear-compare'].focus();nodes['clear-compare'].click();
 check(()=>assert.equal(document.activeElement,nodes['compare-button']));
 check(()=>assert.equal(nodes.comparison.hidden,true));
 check(()=>assert.match(nodes.inspector.textContent,/step\.49999/));
 check(()=>assert.doesNotMatch(nodes.inspector.textContent,/COMPARISON OUTPUT/));
 check(()=>assert.equal(calls().length,250));
 skipDetails();
 skipTo('skip-pages',nodes['previous-page']);
 // Missing-primary divergence has no row to focus and still clamps to an existing page.
 await ctx.readFile(file(normalTape(normalRows.slice(0,500)),'short-primary.tape'),false);
 await ctx.readFile(file(normalTape(normalRows),'longer-comparison.tape'),true);
 nodes['jump-divergence'].click();
 check(()=>assert.equal(calls().length,250));
 check(()=>assert.equal(nodes['visible-count'].textContent,'251–500 of 500 matching calls'));
 check(()=>assert.equal(document.activeElement,nodes.inspector));
 check(()=>assert.match(nodes.inspector.textContent,/missing from the primary/));
 check(()=>assert.match(nodes.inspector.textContent,/tail\.500/));
 skipDetails();
 skipTo('skip-pages',nodes['previous-page']);
 await ctx.readFile(file(normalTape([]),'empty-primary.tape'),false);
 check(()=>assert.equal(nodes['skip-pages'].hidden,true));
 check(()=>assert.equal(nodes['skip-details'].hidden,false));
 skipDetails();
 await ctx.readFile(file(normalTape(normalRows.slice(0,1)),'one-comparison.tape'),true);
 nodes['jump-divergence'].click();
 check(()=>assert.equal(calls().length,0));
 check(()=>assert.equal(document.activeElement,nodes.inspector));
 check(()=>assert.equal(nodes['visible-count'].textContent,'0 of 0 matching calls'));
 check(()=>assert.equal(nodes['skip-pages'].hidden,true));
 check(()=>assert.match(nodes.inspector.textContent,/missing from the primary/));
 skipDetails();
 nodes['clear-compare'].focus();nodes['clear-compare'].click();
 check(()=>assert.equal(document.activeElement,nodes['compare-button']));
 check(()=>assert.match(nodes.inspector.textContent,/Select a recorded call/));
 check(()=>assert.doesNotMatch(nodes.inspector.textContent,/COMPARISON INPUT/));
 skipDetails();
 // A new primary resets the old missing-primary selection and filtered route state.
 await ctx.readFile(file(normalTape(normalRows),'replacement-primary.tape'),false);
 check(()=>assert.equal(run('state.selected'),0));
 check(()=>assert.equal(nodes.comparison.hidden,true));
 check(()=>assert.equal(nodes['skip-pages'].hidden,false));
 skipTo('skip-pages',nodes['next-page']);
 skipDetails();
 console.log('PASS: '+checks+' DOM-model interaction, once-decoded Unicode payload search, bounded pagination through 50,000 valid calls, native skip routes and focus/selection continuity, v1/v2 provenance/closure display, policy warnings, error-filter, comparison, notice ownership, XSS-safe rendering, file-race, and size-limit checks; '+timelineRenders+' timeline renders stayed within 250 calls / 2,750 row elements. (Not a browser rendering test.)');
})().catch(e=>{console.error(e);process.exit(1)});
