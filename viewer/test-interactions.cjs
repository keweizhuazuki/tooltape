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
 setAttribute(k,v){this.attributes[k]=v;}addEventListener(k,fn){(this.listeners[k]??=[]).push(fn);}fire(k,event={}){for(const fn of this.listeners[k]||[])fn(event);}click(){this.fire('click');}focus(){}scrollIntoView(){}
 descendants(){return this.children.flatMap(c=>[c,...c.descendants()]);}querySelectorAll(q){if(q==='button[data-position]')return this.descendants().filter(n=>n.tag==='button'&&n.dataset.position!==undefined);return [];}querySelector(q){let m=q.match(/\[data-position="(\d+)"\]/);return m?this.descendants().find(n=>n.dataset.position===m[1]):null;}
}
const nodes={};for(const m of html.matchAll(/id="([^"]+)"/g))nodes[m[1]]=new Node('div');
const document=new Node('document');document.body=new Node('body');document.getElementById=x=>nodes[x];document.createElement=tag=>new Node(tag);document.createDocumentFragment=()=>new Node('#fragment');
const ctx={TextEncoder,TextDecoder,Uint8Array,atob,btoa,document,console};vm.createContext(ctx);vm.runInContext(script,ctx);
const run=x=>vm.runInContext(x,ctx);let checks=0;const check=f=>{f();checks++};
const file=(text,name='test.tape')=>{let bytes=new TextEncoder().encode(text);return{name,size:bytes.length,arrayBuffer:async()=>bytes.buffer}};
(async()=>{
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
 console.log('PASS: '+checks+' DOM-model interaction, v1/v2 provenance/closure display, policy warnings, error-filter, comparison, XSS-safe rendering, file-race, and size-limit checks. (Not a browser rendering test.)');
})().catch(e=>{console.error(e);process.exit(1)});
