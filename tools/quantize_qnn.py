"""Quantize the fixed-size SR model for the Snapdragon NPU: 16-bit activations / 8-bit weights (A16W8, QDQ).
A16W8 keeps the picture quality of the float model while the NPU runs it with its fast integer units.
Calibration uses real video frames (raw rgb24, 640x360) so the value ranges match what the player feeds in."""
import sys
import numpy as np
from onnxruntime.quantization import CalibrationDataReader, QuantType, quantize
from onnxruntime.quantization.execution_providers.qnn import get_qnn_qdq_config, qnn_preprocess_model

src, frames_path, dst = sys.argv[1], sys.argv[2], sys.argv[3]
W, H = 640, 360
raw = np.fromfile(frames_path, dtype=np.uint8)
n = raw.size // (W * H * 3)
frames = raw[: n * W * H * 3].reshape(n, H, W, 3)
print('calibration frames:', n)


class Reader(CalibrationDataReader):
    def __init__(self, name):
        self.name = name
        self.it = iter(frames)

    def get_next(self):
        f = next(self.it, None)
        if f is None:
            return None
        return {self.name: (f.astype(np.float32) / 255.0).transpose(2, 0, 1)[None].copy()}


import onnx
name = onnx.load(src).graph.input[0].name
pre = dst + '.pre.onnx'
if not qnn_preprocess_model(src, pre):
    pre = src
cfg = get_qnn_qdq_config(pre, Reader(name), activation_type=QuantType.QUInt16, weight_type=QuantType.QUInt8)
quantize(pre, dst, cfg)
m = onnx.load(dst)
print('quantized ops:', sorted({x.op_type for x in m.graph.node}))
m.ir_version = min(m.ir_version, 9)  # ONNX Runtime on the phone reads up to IR 10
onnx.save(m, dst)
