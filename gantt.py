#!/bin/env python3

import matplotlib.pyplot as pyplot
import matplotlib.font_manager as font_manager
import matplotlib.dates
from matplotlib.dates import SECONDLY, DateFormatter, rrulewrapper, RRuleLocator 
import numpy

class Task:
    def __init__(self, start, end, label, score):
        self.start = start
        self.end = end
        self.label = label
        self.score = score
  
def create_gantt(tasks, title):
    swimlanes = tasks.keys()
             
    fig = pyplot.figure(figsize=(20,8))
    subplot = fig.add_subplot(111)

    ymin = -0.1
    ymax = len(swimlanes)*0.5+0.5
    swimlanes_y = numpy.arange(0.5, ymax, 0.5)
    task_bars = []
    task_labels = []
    
    for i, (swimlane, tasks_for_swimlane) in enumerate(tasks.items()):
         for task in tasks_for_swimlane:
             bar = subplot.barh(swimlanes_y[i], task.end - task.start, left=task.start, height=0.3, align='center', edgecolor='black', color=_score_to_color(task.score), alpha = 0.8)
             task_bars.append(bar)
             task_labels.append(task.label)
    
    ylocs, ylabels = pyplot.yticks(swimlanes_y, swimlanes)
    pyplot.setp(ylabels, fontsize = 14)
    subplot.set_ylim(ymin = -0.1, ymax = ymax)

    subplot.xaxis_date()
    rule = rrulewrapper(SECONDLY, interval=1)
    loc = RRuleLocator(rule)
    formatter = DateFormatter("%H:%M:%S")
    subplot.xaxis.set_major_locator(loc)
    subplot.xaxis.set_major_formatter(formatter)
    xlabels = subplot.get_xticklabels()
    pyplot.setp(xlabels, rotation=30, fontsize=10)

    subplot.grid(color = 'g', linestyle = ':')
 
    font = font_manager.FontProperties(size='small')
    subplot.legend(loc=1, prop=font)

    subplot.set_title(title, size=20)
 
    subplot.invert_yaxis()
    fig.autofmt_xdate()

    annot = subplot.annotate("", xy=(0,0), xytext=(-20,20),textcoords="offset points",
                        bbox=dict(boxstyle="round", fc="black", ec="b", lw=2),
                        arrowprops=dict(arrowstyle="->"))
    annot.set_visible(False)

    fig.canvas.mpl_connect("motion_notify_event", lambda event: _hover(event, fig, subplot, annot, task_bars, task_labels))

    return pyplot
 
def _score_to_color(score):
    return ( 1.0-score, score, 0.3 )

def _hover(event, fig, subplot, annot, bar_containers, labels):
    vis = annot.get_visible()
    if event.inaxes == subplot:
        for index, bar_container in enumerate(bar_containers):
            for bar in bar_container:
                cont, ignored = bar.contains(event)
                if cont:
                    _update_annot(annot, bar, labels[index])
                    annot.set_visible(True)
                    fig.canvas.draw_idle()
                    return
    if vis:
        annot.set_visible(False)
        fig.canvas.draw_idle()

def _update_annot(annot, bar, label):
    x = bar.get_x()+bar.get_width()/2.
    y = bar.get_y()
    annot.xy = (x,y)
    annot.set_text(label)
    annot.get_bbox_patch().set_alpha(0.4)

