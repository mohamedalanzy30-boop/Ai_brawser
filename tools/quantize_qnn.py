"""Quantize a fixed-size SR model (tools/fix_model.py) for the Snapdragon NPU: 16-bit activations / 8-bit per-channel weights (A16W8, QDQ).

  quantize_qnn.py model.onnx frames.rgb out.onnx [WxH of the raw rgb24 calibration frames, default 640x360]

Activation ranges are measured on a 640x360 copy of the same graph (same tensor names, only the input size differs):
calibrating the 1174x660 model directly needs more memory than a CI runner has (~70 tensors of 200 MB per frame),
and the value ranges of a convolutional network do not depend on the picture size. The ranges then quantize the real model.
"""
import os, sys, tempfile
import numpy as np
import onnx
from onnx import numpy_helper, shape_inference
from onnxruntime.quantization import CalibrationDataReader, QuantType, quantize_static
from onnxruntime.quantization.execution_providers.qnn import get_qnn_qdq_config, qnn_preprocess_model

src, frames_path, dst = sys.argv[1], sys.argv[2], sys.argv[3]
CW, CH = (int(a) for a in (sys.argv[4] if len(sys.argv) > 4 else '640x360').split('x'))
PW, PH = 640, 360  # calibration size
raw = np.fromfile(frames_path, dtype=np.uint8)
n = raw.size // (CW * CH * 3)
frames = raw[: n * CW * CH * 3].reshape(n, CH, CW, 3)
if (CW, CH) != (PW, PH):
    from PIL import Image
    frames = np.stack([np.asarray(Image.fromarray(f).resize((PW, PH), Image.BICUBIC)) for f in frames])
print('calibration frames:', n)


class Reader(CalibrationDataReader):
    def __init__(self, name, fr):
        self.name, self.it = name, iter(fr)

    def get_next(self):
        f = next(self.it, None)
        return None if f is None else {self.name: (f.astype(np.float32) / 255.0).transpose(2, 0, 1)[None].copy()}


tmp = tempfile.mkdtemp(prefix='qnnq')
m = onnx.load(src)
name = m.graph.input[0].name
# the 640x360 proxy: same nodes, input resized (the network's final Resize still targets the same output size)
proxy = onnx.load(src)
for d, v in zip(proxy.graph.input[0].type.tensor_type.shape.dim, [1, 3, PH, PW]):
    d.ClearField('dim_param'); d.dim_value = v
for o in proxy.graph.output:  # output size is set by the final Resize when there is one, else 4x the input
    has_fit = any(i.name == 'fit_sizes' for i in proxy.graph.initializer)
    if not has_fit:
        for d, v in zip(o.type.tensor_type.shape.dim, [1, 3, PH * 4, PW * 4]):
            d.dim_value = v
del proxy.graph.value_info[:]
proxy = shape_inference.infer_shapes(proxy, strict_mode=True)
onnx.save(proxy, os.path.join(tmp, 'proxy.onnx'))


def pre(path, tag):
    out = os.path.join(tmp, tag + '.pre.onnx')
    return out if qnn_preprocess_model(path, out) else path


def run(model_path, out, reader, cache):
    # per-channel signed 8-bit weights; per-tensor weights drifted the colours more
    cfg = get_qnn_qdq_config(model_path, reader, activation_type=QuantType.QUInt16, weight_type=QuantType.QInt8, per_channel=True)
    quantize_static(model_path, out, cfg.calibration_data_reader, calibrate_method=cfg.calibrate_method, quant_format=cfg.quant_format,
                    activation_type=cfg.activation_type, weight_type=cfg.weight_type, op_types_to_quantize=cfg.op_types_to_quantize,
                    nodes_to_quantize=cfg.nodes_to_quantize, nodes_to_exclude=cfg.nodes_to_exclude, per_channel=cfg.per_channel,
                    reduce_range=cfg.reduce_range, use_external_data_format=cfg.use_external_data_format,
                    calibration_providers=cfg.calibration_providers, extra_options=cfg.extra_options, calibration_cache_path=cache)


cache = os.path.join(tmp, 'ranges.json')
run(pre(os.path.join(tmp, 'proxy.onnx'), 'proxy'), os.path.join(tmp, 'proxy.q.onnx'), Reader(name, frames), cache)
run(pre(src, 'real'), dst, Reader(name, []), cache)

m = onnx.load(dst)
# The quantizer also quantizes Resize's 'scales' constant ([1,1,4,4]); its dequantized value is a hair off 4, which shifts the
# nearest-neighbour base image the network adds its detail to: colour fringes / purple cast, worse than no AI at all (measured with tools/sr_eval.py, which lives on the sr-eval branch, not on main).
# Put the exact float scales back.
src_m = onnx.load(src)
orig = {n.output[0]: numpy_helper.to_array(n.attribute[0].t) for n in src_m.graph.node if n.op_type == 'Constant'}
orig.update({i.name: numpy_helper.to_array(i) for i in src_m.graph.initializer})
for nd in m.graph.node:
    if nd.op_type == 'Resize' and len(nd.input) > 2 and nd.input[2].endswith('_DequantizeLinear_Output'):
        base = nd.input[2][: -len('_DequantizeLinear_Output')]
        if base in orig:
            m.graph.initializer.append(numpy_helper.from_array(orig[base].astype(np.float32), base + '_exact'))
            nd.input[2] = base + '_exact'
            print('resize scales kept exact:', orig[base])
onnx.checker.check_model(m)
print('quantized ops:', sorted({x.op_type for x in m.graph.node}))
m.ir_version = min(m.ir_version, 9)  # ONNX Runtime on the phone reads up to IR 10
onnx.save(m, dst)
