import type { AssetRecord } from './assets';
import type { ConversionOptions } from './workbench';
export function conversionFormats(asset:Pick<AssetRecord,'kind'|'fileName'>):ConversionOptions['format'][] {
  const extension=asset.fileName.split('.').at(-1)?.toLowerCase();
  if(asset.kind==='image')return ['PNG','JPEG','WEBP'];
  if(asset.kind==='web')return ['PDF','PNG'];
  if(extension==='md')return ['HTML','PDF'];
  if(extension==='pdf')return ['PNG','JPEG'];
  if(extension==='csv')return ['JSON'];
  if(extension==='json')return ['CSV'];
  return [];
}
