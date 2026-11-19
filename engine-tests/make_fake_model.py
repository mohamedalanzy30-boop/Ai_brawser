"""Writes fixtures/fake_x4.onnx: a stand-in for the Real-ESRGAN x4 model in the engine tests.
Same contract as the real one (1x3xHxW float in, 1x3x4Hx4W out, any size), but a plain 4x nearest-neighbour
resize, so a test can check orientation and colours exactly. Needs: pip install onnx numpy"""
import os
import numpy as np
import onnx
from onnx import helper, numpy_helper, TensorProto

x = helper.make_tensor_value_info('input', TensorProto.FLOAT, [1, 3, 'h', 'w'])
y = helper.make_tensor_value_info('output', TensorProto.FLOAT, [1, 3, 'h4', 'w4'])
scales = numpy_helper.from_array(np.array([1, 1, 4, 4], np.float32), 'scales')
node = helper.make_node('Resize', ['input', '', 'scales'], ['output'], mode='nearest')
g = helper.make_graph([node], 'fake_x4', [x], [y], [scales])
m = helper.make_model(g, opset_imports=[helper.make_opsetid('', 17)], producer_name='aib-engine-tests')
m.ir_version = 8
onnx.checker.check_model(m)
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'fixtures', 'fake_x4.onnx')
onnx.save(m, out)
print(out, os.path.getsize(out), 'bytes')
