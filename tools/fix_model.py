"""Make the Real-ESRGAN ONNX model fixed-size for the Snapdragon NPU (QNN HTP needs static shapes).

  fix_model.py src dst H W                -> 1x3xHxW in, 1x3x4Hx4W out (the plain 4x model)
  fix_model.py src dst H W OUT_H OUT_W    -> same network, but the NPU also scales the 4x result to OUT_HxOUT_W
                                             (the phone's screen size), so every input size hands the app the same
                                             2560x1440 picture: no 4696x2640 float frames to copy and pack for 660p.
When the result shrinks by more than 1/0.75 it is first averaged 2x2 (no aliasing), then resized bilinearly.
"""
import sys
import onnx
from onnx import helper, numpy_helper, shape_inference, TensorProto
import numpy as np

src, dst, h, w = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
oh, ow = (int(sys.argv[5]), int(sys.argv[6])) if len(sys.argv) > 6 else (h * 4, w * 4)
m = onnx.load(src)
inp = m.graph.input[0]
for d, v in zip(inp.type.tensor_type.shape.dim, [1, 3, h, w]):
    d.ClearField('dim_param'); d.dim_value = v
out = m.graph.output[0]
if (oh, ow) != (h * 4, w * 4):
    raw = out.name + '_x4'
    for n in m.graph.node:
        n.output[:] = [raw if o == out.name else o for o in n.output]
    cur, ch, cw = raw, h * 4, w * 4
    if oh / ch < 0.75:
        m.graph.node.append(helper.make_node('AveragePool', [cur], [raw + '_avg'], kernel_shape=[2, 2], strides=[2, 2]))
        cur, ch, cw = raw + '_avg', ch // 2, cw // 2
    m.graph.initializer.append(numpy_helper.from_array(np.array([1, 3, oh, ow], np.int64), 'fit_sizes'))
    m.graph.node.append(helper.make_node('Resize', [cur, '', '', 'fit_sizes'], [out.name], mode='linear',
                                         coordinate_transformation_mode='half_pixel'))
for d, v in zip(out.type.tensor_type.shape.dim, [1, 3, oh, ow]):
    d.ClearField('dim_param'); d.dim_value = v
del m.graph.value_info[:]
m = shape_inference.infer_shapes(m, strict_mode=True)
onnx.checker.check_model(m)
print('input', inp.name, [d.dim_value for d in m.graph.input[0].type.tensor_type.shape.dim],
      'output', m.graph.output[0].name, [d.dim_value for d in m.graph.output[0].type.tensor_type.shape.dim],
      'ops', sorted({n.op_type for n in m.graph.node}))
m.ir_version = min(m.ir_version, 9)  # ONNX Runtime on the phone reads up to IR 10
onnx.save(m, dst)
