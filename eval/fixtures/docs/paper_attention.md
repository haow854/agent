# 论文卡片：Attention Is All You Need

arXiv 编号：1706.03762 ｜ 年份：2017 ｜ 发表于 NeurIPS 2017

作者：Ashish Vaswani, Noam Shazeer, Niki Parmar, Jakob Uszkoreit, Llion Jones, Aidan N. Gomez, Lukasz Kaiser, Illia Polosukhin

## 摘要

主流的序列转导模型基于复杂的循环或卷积神经网络的编码器-解码器结构。本文提出一种新的简单网络架构 Transformer，仅基于注意力机制，完全放弃递归和卷积。在两个机器翻译任务上的实验表明，这类模型质量更优、并行性更强、训练时间大幅减少：WMT 2014 英德翻译达到 28.4 BLEU，超过当时最佳成果；英法翻译创造单模型最优 41.8 BLEU，仅用 8 块 GPU 训练三天半。模型泛化能力强，在英文成分句法分析上同样表现出色。

## 备注

组内共识：后续所有序列建模的基线从这篇起算。
