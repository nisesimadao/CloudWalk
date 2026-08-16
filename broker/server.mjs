import http from 'node:http';
import crypto from 'node:crypto';

const PORT = Number(process.env.PORT || 8787);
const BASE_URL = (process.env.BASE_URL || '').replace(/\/$/, '');
const CLIENT_ID = process.env.SOUNDCLOUD_CLIENT_ID || '';
const CLIENT_SECRET = process.env.SOUNDCLOUD_CLIENT_SECRET || '';
const CALLBACK = `${BASE_URL}/soundcloud/callback`;
const states = new Map();
const exchanges = new Map();

const b64url = b => b.toString('base64').replace(/=/g,'').replace(/\+/g,'-').replace(/\//g,'_');
const random = n => b64url(crypto.randomBytes(n));
const challenge = verifier => b64url(crypto.createHash('sha256').update(verifier).digest());
const now = () => Date.now();

function json(res, status, body) {
  const data = Buffer.from(JSON.stringify(body));
  res.writeHead(status, {'content-type':'application/json; charset=utf-8','content-length':data.length,'cache-control':'no-store'});
  res.end(data);
}
function redirect(res, url) { res.writeHead(302, {location:url,'cache-control':'no-store'}); res.end(); }
async function body(req) {
  const chunks=[]; for await (const c of req) chunks.push(c);
  return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
}
function configured() { return BASE_URL.startsWith('https://') && CLIENT_ID && CLIENT_SECRET; }
function cleanup() {
  const t=now();
  for (const [k,v] of states) if (v.expires<t) states.delete(k);
  for (const [k,v] of exchanges) if (v.expires<t) exchanges.delete(k);
}

async function tokenRequest(params) {
  const form = new URLSearchParams(params);
  const r = await fetch('https://secure.soundcloud.com/oauth/token', {
    method:'POST',
    headers:{'accept':'application/json; charset=utf-8','content-type':'application/x-www-form-urlencoded'},
    body:form
  });
  const text=await r.text();
  if (!r.ok) throw new Error(`SoundCloud token HTTP ${r.status}: ${text.slice(0,300)}`);
  return JSON.parse(text);
}

const server=http.createServer(async (req,res)=>{
  cleanup();
  const u=new URL(req.url, `http://${req.headers.host}`);
  try {
    if (u.pathname==='/health') return json(res,200,{ok:true,configured:!!configured()});
    if (!configured()) return json(res,503,{error:'broker_not_configured'});

    if (req.method==='GET' && u.pathname==='/soundcloud/start') {
      const appRedirect=u.searchParams.get('redirect_uri') || '';
      if (!appRedirect.startsWith('cloudwalk://auth/callback')) return json(res,400,{error:'invalid_redirect_uri'});
      const state=random(24), verifier=random(48);
      states.set(state,{verifier,appRedirect,expires:now()+10*60_000});
      const auth=new URL('https://secure.soundcloud.com/authorize');
      auth.searchParams.set('client_id',CLIENT_ID);
      auth.searchParams.set('redirect_uri',CALLBACK);
      auth.searchParams.set('response_type','code');
      auth.searchParams.set('code_challenge',challenge(verifier));
      auth.searchParams.set('code_challenge_method','S256');
      auth.searchParams.set('state',state);
      auth.searchParams.set('display','popup');
      return redirect(res,auth.toString());
    }

    if (req.method==='GET' && u.pathname==='/soundcloud/callback') {
      const state=u.searchParams.get('state')||'';
      const saved=states.get(state); states.delete(state);
      if (!saved) return json(res,400,{error:'invalid_or_expired_state'});
      const error=u.searchParams.get('error');
      if (error) return redirect(res,`${saved.appRedirect}?error=${encodeURIComponent(error)}`);
      const code=u.searchParams.get('code')||'';
      const tokens=await tokenRequest({
        grant_type:'authorization_code', client_id:CLIENT_ID, client_secret:CLIENT_SECRET,
        redirect_uri:CALLBACK, code_verifier:saved.verifier, code
      });
      const oneTime=random(32);
      exchanges.set(oneTime,{tokens,appRedirect:saved.appRedirect,expires:now()+2*60_000});
      return redirect(res,`${saved.appRedirect}?code=${encodeURIComponent(oneTime)}`);
    }

    if (req.method==='POST' && u.pathname==='/soundcloud/exchange') {
      const b=await body(req), item=exchanges.get(String(b.code||''));
      if (!item) return json(res,400,{error:'invalid_or_expired_code'});
      if (String(b.redirect_uri||'')!==item.appRedirect) return json(res,400,{error:'redirect_mismatch'});
      exchanges.delete(String(b.code));
      return json(res,200,item.tokens);
    }

    if (req.method==='POST' && u.pathname==='/soundcloud/refresh') {
      const b=await body(req), refresh=String(b.refresh_token||'');
      if (!refresh) return json(res,400,{error:'missing_refresh_token'});
      const tokens=await tokenRequest({grant_type:'refresh_token',client_id:CLIENT_ID,client_secret:CLIENT_SECRET,refresh_token:refresh});
      return json(res,200,tokens);
    }

    return json(res,404,{error:'not_found'});
  } catch (e) {
    console.error(e);
    return json(res,500,{error:'internal_error'});
  }
});
server.listen(PORT,()=>console.log(`CloudWalk broker listening on :${PORT}`));
