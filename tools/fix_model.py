"""Make the Real-ESRGAN ONNX model fixed-size (1x3x360x640 -> 1x3x1440x2560): the Snapdragon NPU (QNN HTP) needs static shapes."""
import sys
import onnx
from onnx import shape_inference

src, dst, h, w = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
m = onnx.load(src)
inp = m.graph.input[0]
dims = inp.type.tensor_type.shape.dim
for d, v in zip(dims, [1, 3, h, w]):
    d.ClearField('dim_param'); d.dim_value = v
for o in m.graph.output:
    for d, v in zip(o.type.tensor_type.shape.dim, [1, 3, h * 4, w * 4]):
        d.ClearField('dim_param'); d.dim_value = v
del m.graph.value_info[:]
m = shape_inference.infer_shapes(m, strict_mode=True)
onnx.checker.check_model(m)
print('input', inp.name, [d.dim_value for d in m.graph.input[0].type.tensor_type.shape.dim],
      'output', m.graph.output[0].name, [d.dim_value for d in m.graph.output[0].type.tensor_type.shape.dim],
      'ops', sorted({n.op_type for n in m.graph.node}))
onnx.save(m, dst)
