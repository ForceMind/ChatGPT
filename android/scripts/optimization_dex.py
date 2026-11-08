"""Minimal checked DEX class and runtime-reference inventory, no code execution."""
import struct,zipfile


def classes_and_roots(apk):
    definitions=set();roots=set()
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not name.endswith('.dex'):continue
            data=archive.read(name);assert data[:4]==b'dex\n' and len(data)>=112
            def u32(at):
                assert 0<=at<=len(data)-4
                return struct.unpack_from('<I',data,at)[0]
            def u16(at):
                assert 0<=at<=len(data)-2
                return struct.unpack_from('<H',data,at)[0]
            ss,so=u32(56),u32(60);ts,to=u32(64),u32(68)
            assert so+ss*4<=len(data) and to+ts*4<=len(data)
            cache={}
            def typ(index):
                assert 0<=index<ts
                if index not in cache:
                    si=u32(to+index*4);assert si<ss
                    at=u32(so+si*4)
                    for n in range(5):
                        assert at<len(data);v=data[at];at+=1
                        if v<128:break
                    else:raise ValueError('Invalid DEX string length')
                    end=data.index(b'\0',at);cache[index]=data[at:end].decode('ascii')
                return cache[index]
            def add(index):
                value=typ(index).lstrip('[')
                if value.startswith('L') and value.endswith(';'):roots.add(value)
            def types(at):
                if at:
                    count=u32(at);assert at+4+count*2<=len(data)
                    for i in range(count):add(u16(at+4+i*2))
            cs,co=u32(96),u32(100);assert co+cs*32<=len(data)
            for i in range(cs):
                at=co+i*32;definitions.add(typ(u32(at)))
                parent=u32(at+8)
                if parent!=0xffffffff:add(parent)
                types(u32(at+12))
            fs,fo=u32(80),u32(84);assert fo+fs*8<=len(data)
            for i in range(fs):add(u16(fo+i*8));add(u16(fo+i*8+2))
            ps,po=u32(72),u32(76);ms,mo=u32(88),u32(92)
            assert po+ps*12<=len(data) and mo+ms*8<=len(data)
            for i in range(ms):
                add(u16(mo+i*8));proto=u16(mo+i*8+2);assert proto<ps
                add(u32(po+proto*12+4));types(u32(po+proto*12+8))
    assert definitions,'No DEX class definitions'
    return definitions,roots
