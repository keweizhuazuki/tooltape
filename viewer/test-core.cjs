'use strict';
// Dependency-free parser, format, comparison, precision, and privacy checks.
// Run from any directory: node path/to/viewer/test-core.cjs
const path = require('node:path');
const fs=require('node:fs'); const vm=require('node:vm'); const assert=require('node:assert/strict');
const html=fs.readFileSync(path.join(__dirname, 'index.html'),'utf8');
const script=html.match(/<script>([\s\S]*?)<\/script>/)[1];new vm.Script(script);
const context={TextDecoder,TextEncoder,atob,btoa,Uint8Array,console};vm.createContext(context);
vm.runInContext(script.slice(0,script.indexOf("$('open-button').addEventListener")),context);
const {parseTape,exampleTape,firstDivergence,prettyJson,durationText,encode,canonicalJson}=context;
let checks=0;const check=(fn)=>{fn();checks++};
const example=exampleTape(); const tape=parseTape(example,'sample');
const row=(name='weather.lookup',input='{}',status='OK',output='{}',index='0',duration='1')=>[index,duration,encode(name),encode(input),status,encode(output)].join('\t');
const wrap=x=>'TOOLTAPE\t1\n'+x+'\n'; const canon=x=>canonicalJson(x,2,'input');
check(()=>assert.equal(tape.calls.length,2));
check(()=>assert.equal(tape.errors,1));
check(()=>assert.equal(tape.totalNanos,538892n));
check(()=>assert.equal(firstDivergence(tape,parseTape(fs.readFileSync(path.join(__dirname, '..', 'fixtures', 'weather-retry.tape'),'utf8'),'fixture')),null));
check(()=>assert.throws(()=>parseTape(example.replaceAll('\n','\r\n'),'CRLF'),/LF line endings/));
check(()=>assert.throws(()=>parseTape(example.trimEnd(),'nonewline'),/final LF/));
check(()=>assert.equal(parseTape('TOOLTAPE\t1\n','empty').calls.length,0));
check(()=>assert.throws(()=>parseTape('\uFEFF'+example,'bom'),/header/));
check(()=>assert.throws(()=>parseTape(wrap(row('','','ERROR','')),'empty fields'),/static identifier/));
check(()=>assert.throws(()=>parseTape(wrap(row('🔎工具')),'unicode'),/static identifier/));
check(()=>assert.equal(parseTape(wrap(row('weather.lookup','{"query":"coffee ☕"}')),'unicode payload').calls[0].input,'{"query":"coffee ☕"}'));
check(()=>assert.throws(()=>parseTape(wrap(row('weather.lookup','""')),'nonobject'),/JSON object/));
check(()=>assert.throws(()=>parseTape(wrap(row('weather.lookup','{}','OK','[]')),'nonobject'),/JSON object/));
check(()=>assert.throws(()=>parseTape(wrap(row('weather.lookup','{}','ERROR','')),'empty error'),/exception class/));
check(()=>assert.throws(()=>parseTape('TOOLTAPE\t3\n','bad'),/header/));
check(()=>assert.throws(()=>parseTape(wrap('0\t1'),'bad'),/6 tab-separated/));
check(()=>assert.throws(()=>parseTape(wrap(row('weather.lookup','{}','NO','{}')),'bad'),/status/));
check(()=>assert.throws(()=>parseTape(wrap(row('weather.lookup','{}','OK','{}','-1')),'bad'),/nonnegative/));
check(()=>assert.throws(()=>parseTape(wrap(row('weather.lookup','{}','OK','{}','1')),'bad'),/contiguous/));
check(()=>assert.throws(()=>parseTape(wrap(row('weather.lookup','{}','OK','{}','0','2.5')),'bad'),/nonnegative/));
check(()=>assert.throws(()=>parseTape(wrap(row('weather.lookup','{}','OK','{}','0','9223372036854775808')),'bad'),/64-bit/));
check(()=>assert.throws(()=>parseTape(wrap(row('weather.lookup','{x:1}')),'bad'),/JSON/));
check(()=>assert.throws(()=>parseTape(wrap(row('weather.lookup','{}','OK','')),'bad'),/JSON/));
check(()=>assert.throws(()=>parseTape(wrap('0\t1\tAA=\te30\tOK\te30'),'bad'),/base64url/));
check(()=>assert.throws(()=>parseTape(wrap('0\t1\tZh\te30\tOK\te30'),'bad'),/base64url/));
check(()=>assert.throws(()=>parseTape(wrap('0\t1\t_w\te30\tOK\te30'),'bad'),/UTF-8/));
check(()=>assert.equal(firstDivergence(tape,parseTape(example.replace('490207','1'),'timing')),null));
check(()=>assert.equal(firstDivergence(tape,parseTape(example.replace(encode('{"city":"Hangzhou"}'),encode('{"city":"Shanghai"}')),'drift')).position,0));
check(()=>assert.equal(firstDivergence(tape,parseTape(example.split('\n').slice(0,2).join('\n')+'\n','short')).position,1));
check(()=>assert.equal(firstDivergence(parseTape('TOOLTAPE\t1\n','empty'),tape).position,0));
check(()=>assert.equal(firstDivergence(parseTape('TOOLTAPE\t1\n','a'),parseTape('TOOLTAPE\t1\n','b')),null));
check(()=>assert.equal(durationText(9223372036854775807n),'9223372036.8 s'));
check(()=>assert.ok(prettyJson('{"big":9223372036854775807,"small":1e-9}').includes('9223372036854775807')));
const pairs=[['1.0','1'],['1e3','1000'],['1.2300','1.23'],['0.0001','0.0001'],['1230e-2','12.3'],['-0.0','0'],['100000000000000000000000','100000000000000000000000'],['-12.400e3','-12400'],['1E+3','1000'],['10.000e-5','0.0001'],['0e10000','0'],['1e-9998','0.'+'0'.repeat(9997)+'1']];
for(const [input,expected] of pairs)check(()=>assert.equal(canon('{"n":'+input+'}'),'{"n":'+expected+'}'));
check(()=>assert.equal(canon('{"z":1.0,"a":2}'),canon('{"a":2.000,"z":1}')));
check(()=>assert.notEqual(canon('{"n":9223372036854775807}'),canon('{"n":9223372036854775808}')));
check(()=>assert.equal(canon('{"n":"😀"}'),'{"n":"\\ud83d\\ude00"}'));
check(()=>assert.throws(()=>canon('{"x":1,"x":2}'),/duplicate/));
check(()=>assert.throws(()=>canon('{"x":1,"\\u0078":2}'),/duplicate/));
check(()=>assert.throws(()=>canon('{"x":'+ '['.repeat(65)+'0'+']'.repeat(65)+'}'),/64 levels/));
for(const bad of ['{"x":01}','{"x":.1}','{"x":NaN}','{"x":1e10001}','{"x":1,}','{"x":[1,]}','{"x":true}x','{"x":"\\uZZZZ"}','{"x":"newline\n"}','{"x":falsex}'])check(()=>assert.throws(()=>canon(bad)));
for(const value of ['{}','{"a":[]}','{"a":[{},[]]}','{"x":"a\\\"b\\nc","q":true,"null":null}','{"x":{"y":1},"z":2}'])check(()=>assert.deepEqual(JSON.parse(prettyJson(canon(value))),JSON.parse(value)));
check(()=>assert.doesNotMatch(script,/innerHTML|outerHTML|insertAdjacentHTML|\bfetch\s*\(|XMLHttpRequest|WebSocket|sendBeacon|localStorage|sessionStorage/));
check(()=>assert.ok(html.includes("connect-src 'none'")));
for (const invalid of ['+0', '00', '01', ' 0', '0 ']) {
  check(() => assert.throws(() => parseTape(wrap(row('weather.lookup', '{}', 'OK', '{}', invalid)), 'invalid index'), /nonnegative/));
  check(() => assert.throws(() => parseTape(wrap(row('weather.lookup', '{}', 'OK', '{}', '0', invalid)), 'invalid duration'), /nonnegative/));
}
// Versioned metadata and recording-session closure are separate from event equality.
const policyId = 'sha256:' + '0123456789abcdef'.repeat(4);
const metadata = JSON.stringify({policyId, source: 'example.weather:v2'});
const v2 = (body = '', meta = metadata, count = body ? String(body.split('\n').length) : '0') =>
  'TOOLTAPE\t2\nMETA\t' + encode(meta) + '\n' + (body ? body + '\n' : '') + (count === null ? '' : 'END\t' + count + '\n');
const exampleRows = example.split('\n').slice(1, -1).join('\n');
const closed = parseTape(v2(exampleRows), 'closed');
const recordedV2 = parseTape(fs.readFileSync(path.join(__dirname, '..', 'fixtures', 'weather-retry-v2.tape'), 'utf8'), 'recorded v2 fixture');
check(() => assert.equal(recordedV2.schemaVersion, 2));
check(() => assert.equal(recordedV2.completeness, 'closed'));
check(() => assert.equal(recordedV2.metadata.source, 'retry-regression'));
check(() => assert.match(recordedV2.metadata.policyId, /^sha256:[0-9a-f]{64}$/));
check(() => assert.equal(firstDivergence(tape, recordedV2), null));
check(() => assert.equal(tape.schemaVersion, 1));
check(() => assert.equal(tape.completeness, 'legacy'));
check(() => assert.equal(tape.metadata, null));
check(() => assert.equal(closed.schemaVersion, 2));
check(() => assert.equal(closed.completeness, 'closed'));
check(() => assert.equal(closed.metadata.policyId, policyId));
check(() => assert.equal(closed.metadata.source, 'example.weather:v2'));
check(() => assert.equal(closed.totalNanos, tape.totalNanos));
check(() => assert.equal(closed.errors, tape.errors));
check(() => assert.equal(firstDivergence(tape, closed), null));
const incomplete = parseTape(v2(exampleRows, metadata, null), 'incomplete');
check(() => assert.equal(incomplete.completeness, 'incomplete'));
check(() => assert.equal(firstDivergence(incomplete, closed), null));
check(() => assert.equal(parseTape(v2(), 'empty closed').completeness, 'closed'));
check(() => assert.equal(parseTape(v2('', metadata, null), 'empty incomplete').completeness, 'incomplete'));
check(() => assert.equal(parseTape(v2(), 'empty closed').calls.length, 0));
check(() => assert.equal(firstDivergence(closed, parseTape(v2(exampleRows, metadata.replace('example.weather:v2', 'different.source')), 'other source')), null));
check(() => assert.equal(firstDivergence(closed, parseTape(v2(exampleRows, metadata.replace(policyId, 'sha256:' + 'f'.repeat(64))), 'other policy')), null));
for (const header of ['TOOLTAPE\t0', 'TOOLTAPE\t02', 'TOOLTAPE\t2 ', 'TOOLTAPE\t2\textra']) {
  check(() => assert.throws(() => parseTape(header + '\n', 'unknown schema'), /header/));
}
for (const invalid of ['TOOLTAPE\t2\n', 'TOOLTAPE\t2\n' + row() + '\n', 'TOOLTAPE\t2\nEND\t0\n', 'TOOLTAPE\t2\nMETA\n', 'TOOLTAPE\t2\nMETA\te30\textra\n']) {
  check(() => assert.throws(() => parseTape(invalid, 'missing metadata'), /Line 2:.*META/));
}
for (const meta of ['{}', '{"source":"static"}', JSON.stringify({policyId}), JSON.stringify({policyId, source: 'static', unknown: true}), JSON.stringify({policyId, source: 'static', constructor: 'unknown'}), '{"__proto__":{},"policyId":"' + policyId + '","source":"static"}']) {
  check(() => assert.throws(() => parseTape(v2('', canon(meta)), 'metadata fields'), /exactly policyId and source/));
}
for (const value of ['', 'sha256:' + 'a'.repeat(63), 'sha256:' + 'a'.repeat(65), 'sha256:' + 'A'.repeat(64), 'sha256:' + 'g'.repeat(64), 'SHA256:' + 'a'.repeat(64), null, 1, {}]) {
  check(() => assert.throws(() => parseTape(v2('', JSON.stringify({policyId: value, source: 'static'})), 'invalid policy'), /policyId must be/));
}
for (const source of ['', 'a'.repeat(129), '<img src=x onerror=alert(1)>', 'two words', 'source/with/path', '工具', null, 1, {}]) {
  check(() => assert.throws(() => parseTape(v2('', JSON.stringify({policyId, source})), 'invalid source'), /source must be/));
}
check(() => assert.equal(parseTape(v2('', JSON.stringify({policyId, source: 'a'.repeat(128)})), 'max source').metadata.source.length, 128));
for (const meta of [metadata + ' ', metadata.replace(',', ', '), JSON.stringify({source: 'static', policyId}), metadata.replace('source', '\\u0073ource')]) {
  check(() => assert.throws(() => parseTape(v2('', meta), 'noncanonical metadata'), /canonical JSON/));
}
for (const meta of ['[]', 'null', '"metadata"']) check(() => assert.throws(() => parseTape(v2('', meta), 'nonobject metadata'), /JSON object/));
check(() => assert.throws(() => parseTape(v2('', '{"policyId":"' + policyId + '","source":"a","source":"b"}'), 'duplicate metadata'), /duplicate/));
for (const encoded of ['e30=', 'Zh', '_w']) check(() => assert.throws(() => parseTape('TOOLTAPE\t2\nMETA\t' + encoded + '\n', 'metadata encoding'), /base64url|UTF-8/));
check(() => assert.throws(() => parseTape(v2(row(), metadata, '0'), 'footer mismatch'), /END event count/));
check(() => assert.throws(() => parseTape(v2('', metadata, '1'), 'empty mismatch'), /END event count/));
for (const count of ['-1', '+0', '00', '01', ' 0', '0 ', '1.0', '', '0\textra']) check(() => assert.throws(() => parseTape(v2('', metadata, count), 'invalid footer count'), /canonical nonnegative decimal/));
for (const trailing of ['END\t0\n', row() + '\n', '\n', 'META\t' + encode(metadata) + '\n']) check(() => assert.throws(() => parseTape(v2() + trailing, 'rows after footer'), /END footer must be the final line/));
check(() => assert.throws(() => parseTape(v2(row()).slice(0, -1), 'footer without LF'), /final LF/));
check(() => assert.throws(() => parseTape(v2(row()).replaceAll('\n', '\r\n'), 'v2 CRLF'), /LF line endings/));
check(() => assert.throws(() => parseTape(v2('META\t' + encode(metadata)), 'second metadata'), /6 tab-separated/));
check(() => assert.throws(() => parseTape(wrap('END\t0'), 'v1 footer'), /6 tab-separated/));
check(() => assert.throws(() => parseTape(v2(row('weather.lookup', '{}', 'OK', '{}', '1')), 'v2 discontinuity'), /Line 3:.*contiguous/));
check(() => assert.throws(() => parseTape(v2(row('weather.lookup', '{}', 'OK', '{}', '0', '01')), 'v2 duration'), /nonnegative/));
check(() => assert.throws(() => parseTape(v2(row('weather.lookup', '{}', 'ERROR', 'arbitrary error text')), 'v2 error'), /exception class/));
const maximumRows = Array.from({length: 50000}, (_, index) => row('tool', '{}', 'OK', '{}', String(index))).join('\n');
check(() => assert.equal(parseTape(v2(maximumRows), 'limit').calls.length, 50000));
check(() => assert.throws(() => parseTape(v2(maximumRows + '\n' + row('tool', '{}', 'OK', '{}', '50000')), 'over limit'), /50,000-call/));
check(() => assert.throws(() => parseTape(wrap(maximumRows + '\n' + row('tool', '{}', 'OK', '{}', '50000')), 'v1 over limit'), /50,000-call/));
// Compressed decimals and escaped Unicode must not bypass decoded-byte limits.
const maxBytes = 16 * 1024 * 1024;
const expandedNumbers = count => '{"n":[' + Array(count).fill('1e9999').join(',') + ']}';
check(() => assert.equal(canon('{"n":1e9999}').length, 10006));
check(() => assert.equal(canon('{"n":-1e9998}').length, 10006));
check(() => assert.equal(canon('{"n":-1e-9997}').length, 10006));
for (const literal of ['1e10000', '-1e9999', '1e-9999', '-1e-9998']) {
  check(() => assert.throws(() => canon('{"n":' + literal + '}'), /canonical number.*10,000/));
}
check(() => assert.equal(canon('{"n":' + '9'.repeat(10000) + '}').length, 10006));
for (const literal of ['9'.repeat(10001), '1e' + '0'.repeat(9999), '0.' + '0'.repeat(9999)]) {
  check(() => assert.throws(() => canon('{"n":' + literal + '}'), /numeric literal.*10,000/));
}
check(() => assert.throws(() => canon(expandedNumbers(2000)), /canonical JSON.*16 MiB/));
check(() => assert.throws(() => canon('{"nested":' + expandedNumbers(2000) + '}'), /canonical JSON.*16 MiB/));
check(() => assert.throws(() => parseTape(wrap(row('tool', expandedNumbers(1000), 'OK', expandedNumbers(1000))), 'expanded fields'), /canonical event data.*16 MiB/));
const expandedRows = [row('tool', expandedNumbers(900)), row('tool', expandedNumbers(900), 'OK', '{}', '1')].join('\n');
check(() => assert.throws(() => parseTape(wrap(expandedRows), 'expanded v1 events'), /canonical event data.*16 MiB/));
check(() => assert.throws(() => parseTape(v2(expandedRows), 'expanded v2 events'), /canonical event data.*16 MiB/));
// Exercise the real ceiling using a ~20 KiB source, including the bytes for
// tool name, status, and a multibyte Unicode exception class in the tape budget.
const nearLimit = expandedNumbers(1677).slice(0, -1) + ',"padding":""}';
const nearLimitBytes = nearLimit.length + 1677 * (10000 - '1e9999'.length);
const paddedInput = extraBytes => nearLimit.slice(0, -2) + 'a'.repeat(extraBytes) + '"}';
check(() => assert.equal(canon(paddedInput(maxBytes - nearLimitBytes)).length, maxBytes));
check(() => assert.throws(() => canon(paddedInput(maxBytes - nearLimitBytes + 1)), /canonical JSON.*16 MiB/));
const errorFieldsBytes = Buffer.byteLength('tool' + 'ERROR' + 'demo.故障', 'utf8');
const eventPadding = maxBytes - nearLimitBytes - errorFieldsBytes;
check(() => assert.equal(parseTape(v2(row('tool', paddedInput(eventPadding), 'ERROR', 'demo.故障')), 'exact event budget').calls[0].output, 'demo.故障'));
check(() => assert.throws(() => parseTape(v2(row('tool', paddedInput(eventPadding + 1), 'ERROR', 'demo.故障')), 'event budget overflow'), /canonical event data.*16 MiB/));
// Check that a failure occurs before the next exponent's padding allocation.
check(() => {
  vm.runInContext('globalThis.paddingCalls = 0; globalThis.originalRepeat = String.prototype.repeat; String.prototype.repeat = function(count) { paddingCalls++; return originalRepeat.call(this, count); };', context);
  try {
    assert.throws(() => canon(expandedNumbers(2000)), /canonical JSON.*16 MiB/);
    assert.equal(context.paddingCalls, 1677);
  } finally {
    vm.runInContext('String.prototype.repeat = originalRepeat; delete globalThis.originalRepeat; delete globalThis.paddingCalls;', context);
  }
});
// Verify exact byte accounting cheaply by giving one value the final few bytes
// of an existing event budget. UTF-8 lengths differ from JavaScript .length.
for (const source of ['{"x":"é"}', '{"x":"工具"}', '{"x":"😀"}', '{"é":"☕"}', '{"x":"\\b\\n\\t"}', '{"x":[true,false,null,{},[]]}']) {
  const expected = canon(source), bytes = Buffer.byteLength(expected, 'utf8');
  check(() => {
    const budget = {bytes: maxBytes - bytes};
    assert.equal(canonicalJson(source, 2, 'input', budget), expected);
    assert.equal(budget.bytes, maxBytes);
  });
  check(() => assert.throws(() => canonicalJson(source, 2, 'input', {bytes: maxBytes - bytes + 1}), /canonical event data.*16 MiB/));
}
check(() => assert.equal(context.utf8Bytes('aé工具😀'), Buffer.byteLength('aé工具😀', 'utf8')));
check(() => assert.throws(() => canon('{"x":"' + 'a'.repeat(maxBytes) + '"}'), /JSON input.*16 MiB/));
check(() => assert.throws(() => canon('{"x":"' + '工具'.repeat(Math.floor(maxBytes / 6)) + '"}'), /JSON input.*16 MiB/));
// A small input can also expand because supplementary characters are escaped.
check(() => assert.throws(() => canon('{"x":"' + '😀'.repeat(Math.floor(maxBytes / 12)) + '"}'), /canonical JSON.*16 MiB/));
check(() => assert.throws(() => parseTape('TOOLTAPE\t1\n' + ' '.repeat(maxBytes) + '\n', 'raw file limit'), /16 MiB viewer limit/));
const nodeArray = count => '{"nodes":[' + Array(count).fill('0').join(',') + ']}';
check(() => assert.equal(JSON.parse(canon(nodeArray(99998))).nodes.length, 99998));
check(() => assert.throws(() => canon(nodeArray(99999)), /100,000 JSON value/));
check(() => assert.throws(() => canon(nodeArray(100001)), /100,000 JSON value/));
check(() => assert.equal(parseTape(wrap(row('tool', '{}', 'ERROR', 'demo.故障')), 'Unicode error class').calls[0].output, 'demo.故障'));
for (const errorClass of ['demo.$Nested_Error', '工具.故障', 'demo.Échec', 'demo.A\u0301', 'demo.\u2160', 'demo.€Failure']) {
  check(() => assert.equal(parseTape(wrap(row('tool', '{}', 'ERROR', errorClass)), 'valid error class').calls[0].output, errorClass));
}
for (const errorClass of ['.Failure', 'demo.', 'demo..Failure', 'demo.1Failure', 'demo.\u200bFailure', 'demo.\u0000Failure', 'demo.😀', 'demo.\u0301Failure']) {
  check(() => assert.throws(() => parseTape(wrap(row('tool', '{}', 'ERROR', errorClass)), 'invalid error class'), /exception class/));
}
check(() => assert.equal(parseTape(wrap(row('tool', '{}', 'ERROR', 'a'.repeat(1024))), 'maximum class name').calls[0].output.length, 1024));
check(() => assert.equal(parseTape(wrap(row('tool', '{}', 'ERROR', '工具'.repeat(512))), 'maximum Unicode class name').calls[0].output.length, 1024));
for (const errorClass of ['a'.repeat(1025), 'a.'.repeat(512) + 'a', '工具'.repeat(513), '𐐀'.repeat(513)]) {
  check(() => assert.throws(() => parseTape(wrap(row('tool', '{}', 'ERROR', errorClass)), 'oversized error class'), /exception class/));
}
console.log('PASS: '+checks+' v1/v2 parser, closure/metadata, comparison, precision, formatting, fixture-parity, limits, and static privacy checks.');
