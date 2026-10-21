export const BROWSER_LANGUAGES = [
  {id:'system',name:'跟随系统'},{id:'zh-CN',name:'简体中文'},{id:'zh-TW',name:'繁體中文'},
  {id:'en-US',name:'English (US)'},{id:'en-GB',name:'English (UK)'},{id:'ja-JP',name:'日本語'},
  {id:'ko-KR',name:'한국어'},{id:'fr-FR',name:'Français'},{id:'de-DE',name:'Deutsch'},
  {id:'es-ES',name:'Español'},{id:'pt-BR',name:'Português (Brasil)'},{id:'ru-RU',name:'Русский'},{id:'ar-SA',name:'العربية'}
] as const;
export const DEFAULT_LANGUAGE='zh-CN';
export function validBrowserLanguage(value:unknown):value is string {return typeof value==='string'&&BROWSER_LANGUAGES.some(l=>l.id===value);}
const zoneValidity=new Map<string,boolean>();
export function validTimeZone(value:unknown):value is string {
 if(typeof value!=='string'||value.length>80)return false;if(value==='system')return true;
 const cached=zoneValidity.get(value);if(cached!==undefined)return cached;let valid=false;
 if(/^[A-Za-z][A-Za-z0-9_+-]*(?:\/[A-Za-z0-9_+.-]+)*$/.test(value))try{new Intl.DateTimeFormat('en',{timeZone:value});valid=true;}catch{/* reject invalid zones */}
 if(zoneValidity.size<1000)zoneValidity.set(value,valid);return valid;
}
export function preferredLanguages(language:string|undefined,system:string[]):string {
 const requested=language&&language!=='system'?[language]:system.length?system:['en-US'];
 const ordered:string[]=[];for(const item of [...requested,'en-US']){let canonical:string;try{canonical=Intl.getCanonicalLocales(item)[0];}catch{continue;}for(const tag of [canonical,canonical.split('-')[0]])if(!ordered.includes(tag))ordered.push(tag);}
 return ordered.join(',');
}
